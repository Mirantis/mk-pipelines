/**
 *
 * Mirror list Docker images
 *
 * Expected parameters:
 *   TARGET_REGISTRY_CREDENTIALS_ID            Credentials for target Docker Registry
 *   TARGET_REGISTRY                           Target Docker Registry name
 *   REGISTRY_URL                              Target Docker Registry URL
 *   IMAGE_TAG                                 Tag to use when pushing images
 *   SOURCE_IMAGE_TAG                          Tag to use when pulling images(optional,if SOURCE_IMAGE_TAG has been found)
 *   SET_DEFAULT_ARTIFACTORY_PROPERTIES        Add extra props. directly to artifactory,
 *   IMAGE_LIST                                List of images to mirror
 *     Example: docker-dev-local.docker.mirantis.net/mirantis/external/docker.elastic.co/elasticsearch
 *              docker-dev-local.docker.mirantis.net/mirantis/external/elasticsearch:${IMAGE_TAG}*
 *              docker-dev-local.docker.mirantis.net/mirantis/external/docker.elastic.co/elasticsearch/elasticsearch:5.4.1
 */

import java.util.regex.Pattern

common = new com.mirantis.mk.Common()
jenkinsUtils = new com.mirantis.mk.JenkinsUtils()

def registryRegex  = Pattern.compile('docker-dev|dev-docker')
def namespaceRegex = Pattern.compile('mirantis|library')
def componentRegex = Pattern.compile('kaas|kaas-bm|oscore')

String external = false
String externalMarker = '/mirantis/external/'
def artifactory = new com.mirantis.mcp.MCPArtifactory()
def artifactoryServer = Artifactory.server('mcp-ci')
def git = new com.mirantis.mk.Git()
def releaseWorkflow = new com.mirantis.mk.ReleaseWorkflow()

// imageList = env.IMAGE_LIST ?: ''
slaveNode = env.SLAVE_NODE ?: 'python'

timeout(time: 4, unit: 'HOURS') {
    node(slaveNode) {
        def user = jenkinsUtils.currentUsername()
        currentBuild.description = "${user}: [${env.SOURCE_IMAGE_TAG} => ${env.IMAGE_TAG}]\n${env.IMAGE_LIST}"
        try {
            allowedGroups = ['release-engineering']
            releaseTags = ['proposed', 'release',  '2018', '2019', '2020']
            tags = [env.SOURCE_IMAGE_TAG, env.IMAGE_TAG]
            tagInRelease = tags.any { tag -> releaseTags.any { tag.contains(it) } }
            if (tagInRelease) {
                if (!jenkinsUtils.currentUserInGroups(allowedGroups)) {
                    throw new Exception("You - ${user} - don't have permissions to run this job with tags ${tags}!")
                } else {
                    echo "User `${user}` belongs to one of groups `${allowedGroups}`. Proceeding..."
                }
            }
            stage('Mirror Docker Images') {
                def images = IMAGE_LIST.tokenize('\n')
                for (image in images) {
                    lengthImageName = image.tokenize('/').size()
                    if (lengthImageName <= 3) {
                        error("Image ${sourceImage} contains very short path")
                    }
                    String src = ''
                    String dst = ''
                    def (sourceImage, sourceTag) = image.trim().tokenize(':')
                    def (sourceRegistry, namespace, componentNamespace) = image.tokenize('/')
                    String componentNamespace = sourceImage.tokenize('/')[-(lengthImageName - 3)..-2].join('/')

                    if (!registryRegex.matcher(sourceRegistry).find() || !namespaceRegex.matcher(namespace).find() || !componentRegex.matcher(component)) {
                        error ("Image ${sourceImage} contains wrong data in path/URL")
                    }
                    if (env.IMAGE_TAG) {
                        targetTag = env.IMAGE_TAG
                    } else {
                        targetTag = sourceTag
                    }
                    String sourceDockerRepo  = sourceRegistry.split(/\./)[0]
                    if (sourceDockerRepo.contains('dev-kaas')) {
                        src = 'dev-'
                        dst = ''
                    } else {
                        src = 'dev'
                        dst = 'prod'
                    }
                    String targetDockerRepo  = sourceDockerRepo.replaceAll("${src}", "${dst}")
                    String targetImage       = sourceImage.replaceAll("${src}", "${dst}")
                    String targetRegistryUrl = sourceRegistry.replaceAll("${src}", "${dst}")
                    String imageRepo = targetImage - targetRegistryUrl
                    if (sourceImage.contains('SUBS_SOURCE_IMAGE_TAG')) {
                        common.warningMsg("Replacing SUBS_SOURCE_IMAGE_TAG => ${env.SOURCE_IMAGE_TAG}")
                        sourceImage = sourceImage.replace('SUBS_SOURCE_IMAGE_TAG', env.SOURCE_IMAGE_TAG)
                    }

                    if (targetImage.contains(externalMarker)) {
                        external = true
                        // check if images exists - raise error, as we don't want to rewrite existing one
                        // http://artifactory.mcp.mirantis.net/test-prod-openstack-docker/mysql/5.6/manifest.json
                        if (artifactory.imageExists(targetRegistryUrl, "${imageRepo}", targetTag)) {
                            echo "Image ${targetImage} already exists!"
                            continue
                        }
                    }
                    common.infoMsg("Attempt to push docker image into remote registry: ${env.REGISTRY_URL}")
                    common.retry(3, 5) {
                       artifactory.promoteDockerArtifact(artifactoryServer.getUrl(),
                                                         sourceDockerRepo,
                                                         targetDockerRepo,
                                                         imageRepo,
                                                         sourceTag,
                                                         targetTag,
                                                         true)
                    }
                }
            }
        } catch (Throwable e) {
            // Stub for future processing
            currentBuild.result = 'FAILURE'
            throw e
        }
    }
}
