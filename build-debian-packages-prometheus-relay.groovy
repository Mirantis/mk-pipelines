def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
def artifactory = new com.mirantis.mk.Artifactory()
def aptly = new com.mirantis.mk.Aptly()

def timestamp = common.getDatetime()
def version = "0.1~${timestamp}"
timeout(time: 12, unit: 'HOURS') {
    node('docker') {
        try{

            stage("cleanup") {
                sh("rm -rf * || true")
            }

            def workingDir = "src/gerrit.mcp.mirantis.com/debian"
            stage("checkout") {
                git.checkoutGitRepository(
                    "${workingDir}/prometheus-relay",
                    "${SOURCE_URL}",
                    SOURCE_BRANCH,
                    SOURCE_CREDENTIALS,
                    true,
                    30,
                    1
                )
            }

            try {

                def jenkinsUID = sh (
                    script: 'id -u',
                    returnStdout: true
                ).trim()
                def imgName = "${OS}-${DIST}-${ARCH}"
                def img

                stage("build image") {
                    img = docker.build(
                        "${imgName}:${timestamp}",
                        [
                            "--build-arg uid=${jenkinsUID}",
                            "--build-arg timestamp=${timestamp}",
                            "-f ${workingDir}/prometheus-relay/docker/${OS}-${DIST}-${ARCH}.Dockerfile",
                            "."
                        ].join(' ')
                    )
                }
                stage("build package") {
                    img.inside{
                        sh("""wget https://storage.googleapis.com/golang/go1.8.1.linux-amd64.tar.gz &&
                            tar xf go1.8.1.linux-amd64.tar.gz &&
                            export GOROOT=\$PWD/go &&
                            export GOPATH=\$PWD &&
                            export PATH=\$PATH:\$GOPATH/bin:\$GOROOT/bin &&
                            cd src/gerrit.mcp.mirantis.com/debian/prometheus-relay &&
                            make""")
                    }
                    archiveArtifacts artifacts: "${workingDir}/prometheus-relay/build/*.deb"
                }
                if (UPLOAD_APTLY.toBoolean()) {
                    lock("aptly-api") {
                        stage("upload") {
                            def buildSteps = [:]
                            def debFiles = sh script: "ls ${workingDir}/prometheus-relay/build/*.deb", returnStdout: true
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

                        stage("rebuild docker images") {
                            build job: "docker-build-images-prometheus", parameters: []
                        }
                    }
                }

            } catch (Exception e) {
                currentBuild.result = 'FAILURE'
                throw e
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
