/**
 *
 * Create debmirror package pipeline
 *
 * Expected parameters:
 * MIRROR_NAME - Name of the mirror
 * MIRROR_URL - URL of mirror
 * ROOT - Root directory of the upstream location
 * METHOD - rsync or http
 * DEBMIRROR_ARGS - args for debmirror comand
 * UPLOAD_URL - URL to upload TAR to
 */

// Load shared libs
def common = new com.mirantis.mk.Common()

timeout(time: 12, unit: 'HOURS') {
    node("python&&disk-xl") {
        try {
            def workspace = common.getWorkspace()
            if(METHOD == "rsync"){
                ROOT = ":mirror/${ROOT}"
            }
            stage("Create mirror"){
                def mirrordir="${workspace}/mirror"
                def debmlog="${workspace}/mirror_${MIRROR_NAME}_log"

                sh "debmirror --verbose --method=${METHOD} --progress --host=${MIRROR_URL} --root=${ROOT} ${DEBMIRROR_ARGS} ${mirrordir}/${MIRROR_NAME} 2>&1 | tee -a ${debmlog}"

                sh "tar -czvf ${workspace}/${MIRROR_NAME}.tar.gz -C ${mirrordir}/${MIRROR_NAME} ."
            }

            stage("Upload mirror"){
                common.retry(3, 5, {
                    uploadImageStatus = sh(script: "curl -f -T ${workspace}/${MIRROR_NAME}.tar.gz ${UPLOAD_URL}", returnStatus: true)
                    if(uploadImageStatus!=0){
                        throw new Exception("Image upload failed")
                    }
                })
            }

        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }finally {
            stage("Cleanup"){
                sh "rm -rf ${workspace}/*"
            }
        }
    }
}