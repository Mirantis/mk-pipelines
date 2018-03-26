def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
def aptly = new com.mirantis.mk.Aptly()
def dockerLib = new com.mirantis.mk.Docker()

def timestamp = common.getDatetime()
def version = "0.1~${timestamp}"
timeout(time: 12, unit: 'HOURS') {
    node('docker') {
        try{

            stage("cleanup") {
                sh("rm -rf * || true")
            }

            stage("checkout") {
                git.checkoutGitRepository(
                    "libvirt-exporter-${version}",
                    "${SOURCE_URL}",
                    SOURCE_BRANCH,
                    SOURCE_CREDENTIALS,
                    true,
                    30,
                    1
                )
            }

            stage("build binary") {
                dir("libvirt-exporter-${version}") {
                    sh("sed -i 's/VERSION/${version}/g' debian/changelog && ./build_static.sh")
                }
            }

            def img = dockerLib.getImage("tcpcloud/debian-build-ubuntu-${DIST}")
            stage("build package") {
                img.inside("-u root:root") {
                    sh("apt-get update && apt-get install ruby ruby-dev && gem install fpm")
                    sh("cd libvirt-exporter-${version} && scripts/build.py --package --version=\"${version}\" --platform=linux --arch=amd64")
                }
                archiveArtifacts artifacts: "libvirt-exporter-${version}/build/*.deb"
            }

            if (UPLOAD_APTLY.toBoolean()) {
                lock("aptly-api") {
                    stage("upload") {
                        def buildSteps = [:]
                        def debFiles = sh(script: "ls libvirt-exporter-${version}/build/*.deb", returnStdout: true)
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
            }

            img.inside("-u root:root") {
                sh("rm -rf * || true")
            }

        } catch (Throwable e) {
           // If there was an exception thrown, the build failed
           currentBuild.result = "FAILURE"
           currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
           throw e
        } finally {
           common.sendNotification(currentBuild.result,"",["slack"])

           if (currentBuild.result != 'FAILURE') {
              sh("rm -rf *")
           }
        }
    }
}
