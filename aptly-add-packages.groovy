/**
 *
 * Aptly add packages pipeline
 *
 * Expected parameters:
 * APTLY_API_URL - URL of Aptly API, example: http://10.1.0.12:8080
 * APTLY_REPO - Name of aptly repo to put packages
 * CLEANUP_REPO - Option to cleanup repo contents after publish
 * PROMOTE - Option to promote the publish
 * PROMOTE_COMPONENT - Component to promote (only used when PROMOTE = True)
 * PROMOTE_SOURCE - Source regex to promote from (only used when PROMOTE = True)
 * PROMOTE_TARGET - Target regex to promote to (only used when PROMOTE = True)
 * PROMOTE_STORAGES - Storages to promote (only used when PROMOTE = True)
 *
 */

// Load shared libs
aptly = new com.mirantis.mk.Aptly()
common = new com.mirantis.mk.Common()

timeout(time: 12, unit: 'HOURS') {
    node("docker&&hardware") {
        try {
            def aptlyServer = ["url": APTLY_API_URL]
            def workspace = common.getWorkspace()
            def actualTime = (System.currentTimeMillis()/1000).toInteger()
            def snapshotName = APTLY_REPO + "-" + actualTime

            lock("aptly-api") {
                stage("upload") {
                    def inputFile = input message: 'Upload file', parameters: [file(name: 'packages.tar.gz')]
                    new hudson.FilePath(new File("$workspace/packages.tar.gz")).copyFrom(inputFile)
                    inputFile.delete()

                    sh "mkdir ${workspace}/packages;tar -xvzf ${workspace}/packages.tar.gz --directory ${workspace}/packages"

                    def packages = sh(script: "ls -1a ${workspace}/packages | tail -n +3", returnStdout: true)
                    packages = packages.tokenize("\n")
                    for(pkg in packages){
                        aptly.uploadPackage("${workspace}/packages/${pkg}", APTLY_API_URL, APTLY_REPO)
                    }
                }

                stage("publish") {
                    aptly.snapshotCreateByAPI(aptlyServer, APTLY_REPO, snapshotName)
                    aptly.publish(APTLY_API_URL)

                    if(PROMOTE.toBoolean()){
                        aptly.promotePublish(APTLY_API_URL, PROMOTE_SOURCE, PROMOTE_TARGET, false, PROMOTE_COMPONENT, null, false, '-d --timeout 600', false, PROMOTE_STORAGES)
                    }

                    if(CLEANUP_REPO.toBoolean()){
                        def packageList = aptly.listPackagesFromRepoByAPI(aptlyServer, APTLY_REPO)
                        aptly.deletePackagesFromRepoByAPI(aptlyServer, APTLY_REPO, packageList)
                    }
                }
            }
            sh "rm -rf ${workspace}/*"
        }
        catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            sh "rm -rf ${workspace}/*"
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}