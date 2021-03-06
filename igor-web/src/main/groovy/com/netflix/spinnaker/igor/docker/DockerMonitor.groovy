/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.igor.docker

import com.netflix.spinnaker.igor.build.model.GenericArtifact
import com.netflix.spinnaker.igor.docker.model.DockerRegistryAccounts
import com.netflix.spinnaker.igor.docker.service.TaggedImage
import com.netflix.spinnaker.igor.history.EchoService
import com.netflix.spinnaker.igor.history.model.DockerEvent
import com.netflix.spinnaker.igor.polling.CommonPollingMonitor
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

import static net.logstash.logback.argument.StructuredArguments.kv

@Service
@SuppressWarnings('CatchException')
@ConditionalOnProperty('dockerRegistry.enabled')
class DockerMonitor extends CommonPollingMonitor {

    @Autowired
    DockerRegistryCache cache

    @Autowired
    DockerRegistryAccounts dockerRegistryAccounts

    @Autowired(required = false)
    EchoService echoService

    @Override
    void initialize() {
    }

    @Override
    void poll() {
        dockerRegistryAccounts.updateAccounts()
        dockerRegistryAccounts.accounts.forEach({ account ->
            changedTags(account)
        })
    }

    private void changedTags(Map accountDetails) {
        String account = accountDetails.name
        Boolean trackDigests = accountDetails.trackDigests ?: false

        log.debug 'Checking for new tags for ' + account
        try {
            List<String> cachedImages = cache.getImages(account)

            def startTime = System.currentTimeMillis()
            List<TaggedImage> images = dockerRegistryAccounts.service.getImagesByAccount(account)
            log.debug("Took ${System.currentTimeMillis() - startTime}ms to retrieve images (account: {})", kv("account", account))

            Map<String, TaggedImage> imageIds = images.collectEntries {
                [(cache.makeKey(account, it.registry, it.repository, it.tag)): it]
            }

            /* Not removing images from igor as we're seeing some reading issues in clouddriver
            Observable.from(cachedImages).filter { String id ->
                !(id in imageIds)
            }.subscribe({ String imageId ->
                log.info "Removing $imageId."
                cache.remove(imageId)
            }, {
                log.error("Error: ${it.message}")
            }
            )
            */

            images.parallelStream().forEach({ TaggedImage image ->
                def imageId = cache.makeKey(account, image.registry, image.repository, image.tag)
                def updateCache = false

                if (imageId in cachedImages) {
                    if (trackDigests) {
                        def lastDigest = cache.getLastDigest(image.account, image.registry, image.repository, image.tag)

                        if (lastDigest != image.digest) {
                            log.info("Updated tagged image: {}: {}. Digest changed from [$lastDigest] -> [$image.digest].", kv("account", image.account), kv("image", imageId))
                            // If either is null, there was an error retrieving the manifest in this or the previous cache cycle.
                            updateCache = image.digest != null && lastDigest != null
                        }
                    }
                } else {
                    log.info("New tagged image: {}: {}. Digest is now [$image.digest].", kv("account", image.account), kv("image", imageId))
                    updateCache = true
                }

                if (updateCache) {
                    postEvent(echoService, cachedImages, image, imageId)
                    cache.setLastDigest(image.account, image.registry, image.repository, image.tag, image.digest)
                }
            })
        } catch (Exception e) {
            log.error("Failed to update account {}", kv("account", account), e)
        }
    }

    @Override
    String getName() {
        "dockerTagMonitor"
    }

    void postEvent(EchoService echoService, List<String> cachedImagesForAccount, TaggedImage image, String imageId) {
        if (!cachedImagesForAccount) {
            // avoid publishing an event if this account has no indexed images (protects against a flushed redis)
            return
        }

        if (!echoService) {
            // avoid publishing an event if echo is disabled
            return
        }

        log.info("Sending tagged image info to echo: {}: {}", kv("account", image.account), kv("image", imageId))
        GenericArtifact dockerArtifact = new GenericArtifact("docker", image.repository, image.tag, "${image.registry}/${image.repository}:${image.tag}")
        dockerArtifact.metadata = [registry: image.registry]

        echoService.postEvent(new DockerEvent(content: new DockerEvent.Content(
            registry: image.registry,
            repository: image.repository,
            tag: image.tag,
            digest: image.digest,
            account: image.account,
        ), artifact: dockerArtifact))
    }
}
