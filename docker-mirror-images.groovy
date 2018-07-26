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
        throw new IllegalArgumentException("Wrong format of image name.")
    }
}
timeout(time: 12, unit: 'HOURS') {
    node("docker") {
        try {
            stage("Mirror Docker Images"){
                def creds = common.getPasswordCredentials(TARGET_REGISTRY_CREDENTIALS_ID)
                sh "docker login --username=${creds.username} --password=${creds.password.toString()} ${REGISTRY_URL}"
                def images = IMAGE_LIST.tokenize('\n')
                def imageName, imagePath, targetRegistry, imageArray
                for (image in images){
                    if(image.trim().indexOf(' ') == -1){
                        throw new IllegalArgumentException("Wrong format of image and target repository input")
                    }
                    imageArray = image.trim().tokenize(' ')
                    imagePath = imageArray[0]
                    if (imagePath.contains('SUBS_SOURCE_IMAGE_TAG')) {
                        common.warningMsg("Replacing SUBS_SOURCE_IMAGE_TAG => ${SOURCE_IMAGE_TAG}")
                        imagePath.replace('SUBS_SOURCE_IMAGE_TAG', SOURCE_IMAGE_TAG)
                    }
                    targetRegistry = imageArray[1]
                    imageName = getImageName(imagePath)
                    sh """docker pull ${imagePath}
                          docker tag ${imagePath} ${targetRegistry}/${imageName}:${IMAGE_TAG}
                          docker push ${targetRegistry}/${imageName}:${IMAGE_TAG}"""
                }
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}
