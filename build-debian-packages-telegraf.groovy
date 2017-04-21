def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
def artifactory = new com.mirantis.mk.Artifactory()
def aptly = new com.mirantis.mk.Aptly()

def timestamp = common.getDatetime()
def version = timestamp

node('docker') {
    try{

        stage("cleanup") {
            sh("rm -rf * || true")
        }

        def workingDir = "src/github.com/influxdata"
        stage("checkout") {
            git.checkoutGitRepository(
                "${workingDir}/telegraf",
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
                        "-f ${workingDir}/telegraf/docker/${OS}-${DIST}-${ARCH}.Dockerfile",
                        "docker"
                    ].join(' ')
                )
            }
            stage("build package") {
                img.inside{
                    sh("""wget https://storage.googleapis.com/golang/go1.8.1.linux-amd64.tar.gz &&
                        tar xf go1.8.1.linux-amd64.tar.gz &&
                        export GOROOT=$PWD/go &&
                        export PATH=$PATH:$GOROOT/bin &&
                        export GOPATH=$PWD &&
                        cd src/github.com/influxdata/telegraf &&
                        scripts/build.py --package --version=\"${version}\" --platform=linux --arch=amd64""")
                }
                archiveArtifacts artifacts: "${workingDir}/*.deb"
            }
            lock("aptly-api") {
                stage("upload") {
                    def buildSteps = [:]
                    def debFiles = sh script: "ls ${workingDir}/telegraf/build/*.deb", returnStdout: true
                    def debFilesArray = debFiles.trim().tokenize()
                    for (int i = 0; i < debFilesArray.size(); i++) {

                        def debFile = debFiles[i];
                        buildSteps[debFiles[i]] = aptly.uploadPackageStep(
                            "${workingDir}/telegraf/build/"+debFile,
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
