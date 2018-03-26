def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
def aptly = new com.mirantis.mk.Aptly()
def dockerLib = new com.mirantis.mk.Docker()

def timestamp = common.getDatetime()
def javaversion = "8"
timeout(time: 12, unit: 'HOURS') {
    node('docker') {
        try {
            def img = dockerLib.getImage("tcpcloud/debian-build-ubuntu-${DIST}")

            if ("${DIST}" == "trusty") {
            	javaversion = "7"
            }

            img.inside ("-u root:root") {
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

            img.inside ("-u root:root") {
                stage("Build") {
                    sh("sed -i \"s/TIMESTAMP/${timestamp}/g\" \$(find ./ -name pom.xml)")
                    sh("sudo apt-get update && sudo apt-get install -y openjdk-${javaversion}-jdk maven")
                    sh("cd jmx-exporter-${timestamp} && mvn package")
                }
            }

            if (UPLOAD_APTLY.toBoolean()) {
                stage("upload package") {
                    def buildSteps = [:]
                    def debFiles = sh script: "find ./ -name *.deb", returnStdout: true
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
                    retry(2){
                      aptly.publish(APTLY_URL)
                    }
                }
            }

            img.inside ("-u root:root") {
                sh("rm -rf * || true")
            }

        } catch (Throwable e) {
           // If there was an exception thrown, the build failed
           currentBuild.result = "FAILURE"
           currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
           throw e
        } finally {
           common.sendNotification(currentBuild.result,"",["slack"])
        }
    }
}
