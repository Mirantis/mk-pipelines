/**
 *
 * Mirror Docker images
 *
 * Expected parameters:
 *   TARGET_REGISTRY_CREDENTIALS_ID            Credentials for target Docker Registry
 *   TARGET_REGISTRY                           Target Docker Registry name
 *   REGISTRY_URL                              Target Docker Registry URL
 *   IMAGE_TAG                                 Tag to use when pushing images
 *   SOURCE_IMAGE_TAG                          Tag to use when pulling images(optional,if SOURCE_IMAGE_TAG has been found)
 *   SET_DEFAULT_ARTIFACTORY_PROPERTIES        Add extra props. directly to artifactory,
 *   IMAGE_LIST                                List of images to mirror
 *     Example: docker.elastic.co/elasticsearch/elasticsearch:5.4.1 docker-prod-local.docker.mirantis.net/mirantis/external/docker.elastic.co/elasticsearch
 *              docker.elastic.co/elasticsearch/elasticsearch:SUBS_SOURCE_IMAGE_TAG docker-prod-local.docker.mirantis.net/mirantis/external/elasticsearch:${IMAGE_TAG}*              Will be proceed like:
 *              docker tag docker.elastic.co/elasticsearch/elasticsearch:5.4.1 docker-prod-local.docker.mirantis.net/mirantis/external/docker.elastic.co/elasticsearch/elasticsearch:5.4.1
 *
 *
 */
import java.util.regex.Pattern
import groovy.json.JsonSlurper

common = new com.mirantis.mk.Common()
external = false
externalMarker = '/mirantis/external/'

slaveNode = env.SLAVE_NODE ?: 'docker'
setDefaultArtifactoryProperties = env.SET_DEFAULT_ARTIFACTORY_PROPERTIES ?: true

def getImageName(String image) {
    def regex = Pattern.compile('(?:.+/)?([^:]+)(?::.+)?')
    def matcher = regex.matcher(image)
    if (matcher.find()) {
        def imageName = matcher.group(1)
        return imageName
    } else {
        error("Wrong format of image name.")
    }
}

timeout(time: 4, unit: 'HOURS') {
    node(slaveNode) {
        try {
            stage("Mirror Docker Images") {

                def images = IMAGE_LIST.tokenize('\n')
                def imageName, sourceImage, targetRegistryPath, imageArray
                for (image in images) {
                    if (image.trim().indexOf(' ') == -1) {
                        error("Wrong format of image and target repository input")
                    }
                    imageArray = image.trim().tokenize(' ')
                    sourceImage = imageArray[0]
                    if (sourceImage.contains('SUBS_SOURCE_IMAGE_TAG')) {
                        common.warningMsg("Replacing SUBS_SOURCE_IMAGE_TAG => ${env.SOURCE_IMAGE_TAG}")
                        sourceImage = sourceImage.replace('SUBS_SOURCE_IMAGE_TAG', env.SOURCE_IMAGE_TAG)
                    }
                    targetRegistryPath = imageArray[1]
                    targetRegistry = imageArray[1].split('/')[0]
                    imageName = getImageName(sourceImage)
                    targetImageFull = "${targetRegistryPath}/${imageName}:${env.IMAGE_TAG}"
                    srcImage = docker.image(sourceImage)
                    srcImage.pull()
                    // Use sh-docker call for tag, due magic code in plugin:
                    // https://github.com/jenkinsci/docker-workflow-plugin/blob/docker-workflow-1.17/src/main/resources/org/jenkinsci/plugins/docker/workflow/Docker.groovy#L168-L170
                    sh("docker tag ${srcImage.id} ${targetImageFull}")
                    common.infoMsg("Attempt to push docker image into remote registry: ${env.REGISTRY_URL}")
                    sh("docker push ${targetImageFull}")
                    if (targetImageFull.contains(externalMarker)) {
                        external = true
                    }

                    if (setDefaultArtifactoryProperties) {
                        common.infoMsg("Processing artifactory props for : ${targetImageFull}")
                        LinkedHashMap artifactoryProperties = [:]
                        // Get digest of pushed image
                        String unique_image_id = sh(
                            script: "docker inspect --format='{{index .RepoDigests 0}}' '${targetImageFull}'",
                            returnStdout: true,
                        ).trim()
                        def image_sha256 = unique_image_id.tokenize(':')[1]
                        def ret = new URL("https://${targetRegistry}/artifactory/api/search/checksum?sha256=${image_sha256}").getText()
                        // Most probably, we would get many images, especially for external images. We need to guess
                        // exactly one, which we pushing now
                        guessImage = targetImageFull.replace(':', '/').replace(targetRegistry, '')
                        ArrayList img_data = new JsonSlurper().parseText(ret)['results']
                        img_data*.uri.each { imgUrl ->
                            if (imgUrl.contains(guessImage)) {
                                artifactoryProperties = [
                                    'com.mirantis.targetTag'    : env.IMAGE_TAG,
                                    'com.mirantis.uniqueImageId': unique_image_id,
                                ]
                                if (external) {
                                    artifactoryProperties << ['com.mirantis.externalImage': external]
                                }
                                common.infoMsg("artifactoryProperties=> ${artifactoryProperties}")
                                // Call pipeline-library routine to set properties
                                def mcp_artifactory = new com.mirantis.mcp.MCPArtifactory()
                                mcp_artifactory.setProperties(imgUrl - '/manifest.json', artifactoryProperties)
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            // Stub for future processing
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}
