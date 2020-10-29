/**
 * File upload to AWS S3 pipeline
 *
 * Expected parameters:
 *   DEST         S3 bucket to upload files to (e.g. s3://images-mirantis-com)
 *   IMAGE        The image with s4cmd utility installed
 *   SOURCE       The source URL to download files from (e.g. http://images.mcp.mirantis.net)
 *   FILENAMES    Relative path to files. (SOURCE and FILENAMES values are being
 *                concantenated to form URL to download files from.)
 *   FORCE_UPLOAD Force file upload (will overwrite existing destination file)
 */

def common = new com.mirantis.mk.Common()
def s4cmdOpts = ' --sync-check '
Boolean forceUpload = (env.FORCE_UPLOAD ?: false).toBoolean()
def wgetTries = env.WGET_TRIES ?: '50'

node("docker") {
    stage('Prepare') {
        img = docker.image(IMAGE)
        img.pull()
    }
    stage('Upload') {
        FILENAMES.split().each { filenamePath ->
            url = "${SOURCE}/${filenamePath}"
            filename = filenamePath.tokenize('/')[-1]
            opts = s4cmdOpts
            if(filenamePath.contains('stable') || forceUpload) {
                common.warningMsg("Going to ovveride ${filenamePath}")
                opts = opts + '--force'
            }
            img.withRun("--entrypoint='/bin/bash'") { c ->
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'aws-s3',
                                  usernameVariable: 'S3_ACCESS_KEY', passwordVariable: 'S3_SECRET_KEY']]) {
                    img.inside("-e S3_ACCESS_KEY=${S3_ACCESS_KEY} -e S3_SECRET_KEY=${S3_SECRET_KEY}") {
                        common.retry(3, 5) {
                            sh(script: "wget --progress=dot:giga --tries=${wgetTries} -O ${filename} ${url}", returnStdout: true)
                            sh(script: "/usr/local/bin/s4cmd put ${opts} ${filename} ${DEST}/${filenamePath}", returnStdout: true)
                        }
                    }
                }


            }
            sh("rm ${filename}")
        }
    }
    deleteDir()
}
