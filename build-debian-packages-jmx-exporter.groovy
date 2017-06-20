def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
def aptly = new com.mirantis.mk.Aptly()

def timestamp = common.getDatetime()

node('docker') {
    try{

        stage("cleanup") {
            sh("rm -rf * || true")
        }

        stage("checkout") {
            git.checkoutGitRepository(
                "jmx-exporter-${timestamp}",
                "${SOURCE_URL}",
                SOURCE_BRANCH,
                SOURCE_CREDENTIALS,
                true,
                30,
                1
            )
        }

        try {
            def img = docker.img("tcpcloud/debian-build-ubuntu-xenial")

            img.inside {
                stage("Build") {
                    sh("sed -i \"s/TIMESTAMP/${timestamp}/g\" \$(find -name pom.xml)")
                    sh("sudo apt-get update && sudo apt-get install -y openjdk-8-jdk maven")
                    sh("cd jmx-exporter-${timestamp} && mvn package")
                }
            }

            if (UPLOAD_APTLY.toBoolean()) {
                stage("upload package") {
                    def buildSteps = [:]
                    def debFiles = sh script: "find -name *.deb", returnStdout: true
                    def debFilesArray = debFiles.trim().tokenize()
                    def workspace = common.getWorkspace()
                    for (int i = 0; i < debFilesArray.size(); i++) {
                        def debFile = debFilesArray[i];
                        buildSteps[debFiles[i]] = aptly.uploadPackageStep(
                            "${workspace}/"+debFile,
                            APTLY_URL,
                            APTLY_REPO,
                            true
                        )
                    }
                    parallel buildSteps
                }

                stage("publish") {
                    aptly.snapshotRepo(APTLY_URL, APTLY_REPO, timestamp)
                    aptly.publish(APTLY_URL)
                }
            }

        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            println "Cleaning up docker images"
            sh("docker images | grep -E '[-:\\ ]+${timestamp}[\\.\\ /\$]+' | awk '{print \$3}' | xargs docker rmi -f || true")
            throw e
        }

    } catch (Throwable e) {
       // If there was an exception thrown, the build failed
       currentBuild.result = "FAILURE"
       throw e
    } finally {
       common.sendNotification(currentBuild.result,"",["slack"])

       if (currentBuild.result != 'FAILURE') {
          sh("rm -rf *")
       }
    }
}
