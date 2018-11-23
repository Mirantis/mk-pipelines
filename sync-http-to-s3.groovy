def common = new com.mirantis.mk.Common()
s4cmdOpts = ' --sync-check '

node("docker") {
    stage('Prepare') {
        img = docker.image(IMAGE)
        img.pull()
    }
    stage('Upload') {
        FILENAMES.split().each { filename ->
            url = "${SOURCE}/${filename}"
            opts = s4cmdOpts
            if(filename.contains('stable')){
                common.warningMsg("Going to ovveride ${filename}")
                opts = opts + '--force'
            }
            img.withRun("--entrypoint='/bin/bash'") { c ->
                withCredentials([[$class          : 'UsernamePasswordMultiBinding', credentialsId: 'aws-s3',
                                  usernameVariable: 'S3_ACCESS_KEY', passwordVariable: 'S3_SECRET_KEY']]) {
                    img.inside("-e S3_ACCESS_KEY=${S3_ACCESS_KEY} -e S3_SECRET_KEY=${S3_SECRET_KEY}") {
                        common.retry(3, 5) {
                            sh(script: "wget --progress=dot:giga -O ${filename} ${url}", returnStdout: true)
                            sh(script: "/usr/local/bin/s4cmd put ${opts} ${filename} ${DEST}/${filename}", returnStdout: true)
                        }
                    }
                }


            }
            sh("rm ${filename}")
        }
    }
    deleteDir()
}
