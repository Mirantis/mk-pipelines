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
jenkinsUtils = new com.mirantis.mk.JenkinsUtils()
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

def getImageInfo(String imageName) {
    String unique_image_id = sh(
        script: "docker inspect --format='{{index .RepoDigests 0}}' '${imageName}'",
        returnStdout: true,
    ).trim()
    String imageSha256 = unique_image_id.tokenize(':')[1]
    common.infoMsg("Docker ${imageName} image sha256 is ${imageSha256}")
    return [ 'id': unique_image_id, 'sha256': imageSha256 ]
}

def imageURL(String registry, String imageName, String sha256) {
    def ret = new URL("https://${registry}/artifactory/api/search/checksum?sha256=${sha256}").getText()
    // Most probably, we would get many images, especially for external images. We need to guess
    // exactly one, which we pushing now
    def tgtGuessImage = imageName.replace(':', '/').replace(registry, '')
    ArrayList img_data = new JsonSlurper().parseText(ret)['results']
    def tgtImgUrl = img_data*.uri.find { it.contains(tgtGuessImage) }
    if (tgtImgUrl) {
        return tgtImgUrl
    } else {
        error("Can't find image ${imageName} in registry ${registry} with sha256: ${sha256}!")
    }
}

timeout(time: 4, unit: 'HOURS') {
    node(slaveNode) {
        def user = ''
        wrap([$class: 'BuildUser']) {
            user = env.BUILD_USER_ID
        }
        currentBuild.description = "${user}: [${env.SOURCE_IMAGE_TAG} => ${env.IMAGE_TAG}]\n${env.IMAGE_LIST}"
        try {
            allowedGroups = ['release-engineering']
            releaseTags = ['proposed', 'release', 'testing', '2018', '2019', '2020']
            tags = [env.SOURCE_IMAGE_TAG, env.IMAGE_TAG]
            tagInRelease = tags.any { tag -> releaseTags.any { tag.contains(it) } }
            if (tagInRelease) {
                if (!jenkinsUtils.currentUserInGroups(allowedGroups)) {
                    error: "You - ${currentUserName} - don't have permissions to run this job with tags ${tags}!"
                } else {
                    echo "User `${currentUserName}` belongs to group `${env.JENKINS_ADMIN_GROUP}`. Proceeding..."
                }
            }
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

                    def mcp_artifactory = new com.mirantis.mcp.MCPArtifactory()
                    if (targetImageFull.contains(externalMarker)) {
                        external = true
                        // check if images exists - raise error, as we don't want to rewrite existing one
                        def imageRepo = targetRegistryPath - targetRegistry
                        if (mcp_artifactory.imageExists(env.REGISTRY_URL, "${imageRepo}/${imageName}", env.IMAGE_TAG)) {
                            error("Image ${targetImageFull} already exists!")
                        }
                    }

                    srcImage = docker.image(sourceImage)
                    common.retry(3, 5) {
                        srcImage.pull()
                    }
                    source_image_sha256 = getImageInfo(sourceImage)['sha256']
                    // Use sh-docker call for tag, due magic code in plugin:
                    // https://github.com/jenkinsci/docker-workflow-plugin/blob/docker-workflow-1.17/src/main/resources/org/jenkinsci/plugins/docker/workflow/Docker.groovy#L168-L170
                    sh("docker tag ${srcImage.id} ${targetImageFull}")
                    common.infoMsg("Attempt to push docker image into remote registry: ${env.REGISTRY_URL}")
                    common.retry(3, 5) {
                        docker.withRegistry(env.REGISTRY_URL, env.TARGET_REGISTRY_CREDENTIALS_ID) {
                            sh("docker push ${targetImageFull}")
                        }
                    }
                    def buildTime = new Date().format("yyyyMMdd-HH:mm:ss.SSS", TimeZone.getTimeZone('UTC'))

                    if (setDefaultArtifactoryProperties) {
                        common.infoMsg("Processing artifactory props for : ${targetImageFull}")
                        LinkedHashMap artifactoryProperties = [:]
                        def tgtImageInfo = getImageInfo(targetImageFull)
                        def tgt_image_sha256 = tgtImageInfo['sha256']
                        def unique_image_id = tgtImageInfo['id']
                        def tgtImgUrl = imageURL(targetRegistry, targetImageFull, tgt_image_sha256) - '/manifest.json'
                        artifactoryProperties = [
                            'com.mirantis.targetTag'    : env.IMAGE_TAG,
                            'com.mirantis.uniqueImageId': unique_image_id,
                        ]
                        if (external) {
                            artifactoryProperties << ['com.mirantis.externalImage': external]
                        }
                        def historyProperties = []
                        try {
                            def sourceRegistry = sourceImage.split('/')[0]
                            def sourceImgUrl = imageURL(sourceRegistry, sourceImage, source_image_sha256) - '/manifest.json'
                            def existingProps = mcp_artifactory.getPropertiesForArtifact(sourceImgUrl)
                            // check does the source image have already history props
                            if (existingProps) {
                                historyProperties = existingProps.get('com.mirantis.versionHistory', [])
                            }
                        } catch (Exception e) {
                            common.warningMsg("Can't find history for ${sourceImage}.")
                        }
                        // %5C - backslash symbol is needed
                        historyProperties.add("${buildTime}%5C=${sourceImage}")
                        artifactoryProperties << [ 'com.mirantis.versionHistory': historyProperties.join(',') ]
                        common.infoMsg("artifactoryProperties=> ${artifactoryProperties}")
                        common.retry(3, 5) {
                            mcp_artifactory.setProperties(tgtImgUrl, artifactoryProperties)
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
