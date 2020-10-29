def common = new com.mirantis.mk.Common()
def s4cmdOpts = ' --sync-check '
Boolean forceUpload = (env.FORCE_UPLOAD ?: false).toBoolean()

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
                            sh(script: "wget --progress=dot:giga --tries=50 -O ${filename} ${url}", returnStdout: true)
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
