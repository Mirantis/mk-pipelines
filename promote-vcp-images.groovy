/**
 *
 * Promote VCP(qcow2) images
 *
 * Expected parameters:
 *   VCP_IMAGE_LIST - multiline with qcow2 file names
 *   TAG            - Target tag of image.Possible are: "nightly|testing|proposed|201X.X.X"
 *   SOURCE_TAG     - Initial tag to be tagged with TAG. Will replace SUBS_SOURCE_VCP_IMAGE_TAG in VCP_IMAGE_LIST
 *   UPLOAD_URL     - WebDav url with creds, from\to download images
 *
 */

def common = new com.mirantis.mk.Common()
def jenkinsUtils = new com.mirantis.mk.JenkinsUtils()

// Better to chose slave with ssd and fast network to webDav host
slaveNode = env.SLAVE_NODE ?: 'jsl23.mcp.mirantis.net'
def job_env = env.getEnvironment().findAll { k, v -> v }
def verify = job_env.VERIFY_DOWNLOAD ?: true
def overwrite = job_env.FORCE_OVERWRITE.toBoolean() ?: false



timeout(time: 6, unit: 'HOURS') {
    node(slaveNode) {

        String description = ''
        insufficientPermissions = false
        try {
            // Pre-run verify
            // promote is restricted to users in aptly-promote-users LDAP group
            if (!jenkinsUtils.currentUserInGroups(["mcp-cicd-admins", "aptly-promote-users"])) {
                insufficientPermissions = true
                error(String.format("You don't have permissions to make promote from source:%s to target:%s! Only CI/CD and QA team can perform promote.", job_env.SOURCE_TAG, job_env.TAG))
            }
            // Check for required opts
            for (opt in ['UPLOAD_URL', 'SOURCE_TAG', 'TAG', 'VCP_IMAGE_LIST']) {
                if (!job_env.get(opt, null)) {
                    error("Invalid input params, at least ${opt} param missing")
                }
            }
            def images = job_env.VCP_IMAGE_LIST.trim().tokenize()
            for (image in images) {
                if (image.startsWith('#')) {
                    common.warningMsg("Skipping image ${image}")
                    continue
                }
                common.infoMsg("Replacing SUBS_SOURCE_VCP_IMAGE_TAG => ${job_env.SOURCE_TAG}")
                sourceImage = image.replace('SUBS_SOURCE_VCP_IMAGE_TAG', job_env.SOURCE_TAG)
                targetImage = image.replace('SUBS_SOURCE_VCP_IMAGE_TAG', job_env.TAG)

                // TODO: normalize url's?
                sourceImageUrl = job_env.UPLOAD_URL + '/' + sourceImage
                sourceImageMd5Url = job_env.UPLOAD_URL + '/' + sourceImage + '.md5'
                targetImageUrl = job_env.UPLOAD_URL + '/' + targetImage
                targetImageMd5Url = job_env.UPLOAD_URL + '/' + targetImage + '.md5'

                common.infoMsg("Attempt to download: ${sourceImage} => ${targetImage}")
                common.retry(3, 5) {
                    sh(script: "wget --progress=dot:giga --auth-no-challenge -O ${targetImage} ${sourceImageUrl}")
                }
                def targetImageMd5 = common.cutOrDie("md5sum ${targetImage} | tee ${targetImage}.md5", 0)
                if (verify.toBoolean()) {
                    common.infoMsg("Checking md5's ")
                    sh(script: "wget --progress=dot:giga --auth-no-challenge -O ${targetImage}_source_md5 ${sourceImageMd5Url}")
                    def sourceImageMd5 = readFile(file: "${targetImage}_source_md5").tokenize(' ')[0]
                    // Compare downloaded and remote files
                    if (sourceImageMd5 != targetImageMd5) {
                        error("Image ${targetImage} md5sum verify failed!")
                    } else {
                        common.infoMsg("sourceImageMd5: ${sourceImageMd5} == target to upload ImageMd5: ${targetImageMd5}")
                    }
                    // Compare downloaded file, and remote file-to-be-promoted. If same - no sense to promote same file
                    remoteImageMd5Status = sh(script: "wget --progress=dot:giga --auth-no-challenge -O ${targetImage}_expected_target_md5 ${targetImageMd5Url}", returnStatus: true)
                    if (remoteImageMd5Status == '8') {
                        common.infoMsg("target to upload ImageMd5 file not even exist.Continue..")
                    } else {
                        def remoteImageMd5 = readFile(file: "${targetImage}_expected_target_md5").tokenize(' ')[0]
                        if (sourceImageMd5 == remoteImageMd5) {
                            common.infoMsg("sourceImageMd5: ${sourceImageMd5} and target to upload ImageMd5: ${targetImageMd5} are same")
                            common.warningMsg("Skipping to upload: ${targetImage} since it already same")
                            description += "Skipping to upload: ${targetImage} since it already same\n"
                            continue
                        }
                    }
                    common.infoMsg("Check, that we are not going to overwrite released file..")
                    if (['proposed', 'testing', 'nightly'].contains(job_env.TAG)) {
                        common.infoMsg("Uploading to ${job_env.TAG} looks safe..")
                    } else if (['stable'].contains(job_env.TAG)) {
                        common.warningMsg("Uploading to ${job_env.TAG} not safe! But still possible")
                    } else {
                        common.warningMsg("Looks like uploading to new release: ${job_env.TAG}. Checking, that it is not exist yet..")
                        remoteImageStatus = ''
                        remoteImageStatus = sh(script: "wget  --auth-no-challenge --spider ${targetImageUrl} 2>/dev/null", returnStatus: true)
                        // wget return code 8 ,if file not exist
                        if (remoteImageStatus != 8 && !overwrite) {
                            error("Attempt to overwrite existing release! Target: ${targetImage} already exist!")
                        }
                    }
                }

                common.infoMsg("Attempt to UPLOAD: ${targetImage} => ${targetImageUrl}")
                //
                def uploadImageStatus = ''
                def uploadImageMd5Status = ''
                common.retry(3, 5) {
                    uploadImageStatus = sh(script: "curl -f -T ${targetImage} ${job_env.UPLOAD_URL}", returnStatus: true)
                    if (uploadImageStatus != 0) {
                        error("Uploading file: ${targetImage} failed!")
                    }
                }
                uploadImageMd5Status = sh(script: "curl -f -T ${targetImage}.md5 ${job_env.UPLOAD_URL}", returnStatus: true)
                if (uploadImageMd5Status != 0) {
                    error("Uploading file: ${targetImage}.md5 failed!")
                }

                description += "<a href='http://apt.mirantis.net:8085/images/${targetImage}'>${job_env.SOURCE_TAG}=>${targetImage}</a>"
            }
            currentBuild.description = description
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            if (insufficientPermissions) {
                currentBuild.result = "ABORTED"
                currentBuild.description = "Promote aborted due to insufficient permissions"
            } else {
                currentBuild.result = "FAILURE"
                currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            }
            throw e
        }
        finally {
            common.infoMsg("Cleanup..")
            sh(script: 'find . -mindepth 1 -delete > /dev/null || true')
        }
    }
}
