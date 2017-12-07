/**
 *
 * Mirror Docker images
 *
 * Expected parameters:
 *   TARGET_REGISTRY_CREDENTIALS_ID            Credentials for target Docker Registry
 *   TARGET_REGISTRY                           Target Docker Registry name
 *   REGISTRY_URL                              Target Docker Registry URL
 *   IMAGE_TAG                                 Tag to use when pushing images
 *   IMAGE_LIST                                List of images to mirror
 *
 */
import java.util.regex.Pattern;

def common = new com.mirantis.mk.Common()

@NonCPS
def getImageName(String image) {
    def regex = Pattern.compile('(?:.+/)?([^:]+)(?::.+)?')
    def matcher = regex.matcher(image)
    if(matcher.find()){
        def imageName = matcher.group(1)
        return imageName
    }else{
        throw new IllegalFormatException("Wrong format of image name.")
    }
}

node("docker") {
    try {
        stage("Mirror Docker Images"){
            def creds = common.getPasswordCredentials(TARGET_REGISTRY_CREDENTIALS_ID)
            sh "docker login --username=${creds.username} --password=${creds.password.toString()} ${REGISTRY_URL}"
            def images = IMAGE_LIST.tokenize('\n')
            def imageName
            for (image in images){
                sh "echo ${image}"
                imageName = getImageName(image)
                sh "docker pull ${image}"
                sh "docker tag ${image} ${TARGET_REGISTRY}/${imageName}:${IMAGE_TAG}"
                sh "docker push ${TARGET_REGISTRY}/${imageName}:${IMAGE_TAG}"
            }
        }
    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    }
}