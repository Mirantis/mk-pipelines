/**
 *
 * Build mirror image pipeline
 *
 * Expected parameters:
 * IMAGE_NAME - Name of the result image.
 * OS_CREDENTIALS_ID - ID of credentials for OpenStack API stored in Jenkins.
 * OS_PROJECT - Project in OpenStack under the VM will be spawned.
 * OS_URL - Keystone auth endpoint of the OpenStack.
 * OS_VERSION - OpenStack version
 * UPLOAD_URL - URL of an WebDAV used to upload the image after creating.
 * VM_AVAILABILITY_ZONE - Availability zone in OpenStack in the VM will be spawned.
 * VM_FLAVOR - Flavor to be used for VM in OpenStack.
 * VM_FLOATING_IP_POOL - Floating IP pool to be used to assign floating IP to the VM.
 * VM_IMAGE - Name of the image to be used for VM in OpenStack.
 * VM_IP - Static IP that is assigned to the VM which belongs to the network used.
 * VM_NETWORK_ID - ID of the network that VM connects to.
 * EXTRA_VARIABLES - list of key:value variables required by template.json
 *
 */

// Load shared libs
def common = new com.mirantis.mk.Common()
def openstack = new com.mirantis.mk.Openstack()
def git = new com.mirantis.mk.Git()
def date = new Date()
def dateTime = date.format("ddMMyyyy-HHmmss")
def rcFile = ""
def openstackEnv = ""
def uploadImageStatus = ""
def uploadMd5Status = ""
def creds
ArrayList extra_vars = EXTRA_VARIABLES.readLines()
IMAGE_NAME = IMAGE_NAME + "-" + dateTime

timeout(time: 8, unit: 'HOURS') {
  node("python&&disk-xl") {
    try {
      def workspace = common.getWorkspace()
      openstackEnv = "${workspace}/venv"

      stage("Prepare env") {
        if (!fileExists("${workspace}/tmp")) {
          sh "mkdir -p ${workspace}/tmp"
        }
        if (!fileExists("${workspace}/images")) {
          sh "mkdir ${workspace}/images"
        }
        if (!fileExists("bin")) {
          common.infoMsg("Downloading packer")
          sh "mkdir -p bin"
          dir("bin") {
            sh "wget --quiet -O ${PACKER_ZIP} ${PACKER_URL}"
            sh "echo \"${PACKER_ZIP_MD5} ${PACKER_ZIP}\" >> md5sum"
            sh "md5sum -c --status md5sum"
            sh "unzip ${PACKER_ZIP}"
          }
        }
        // clean images dir before building
        sh(script: "rm -rf ${BUILD_OS}/images/*", returnStatus: true)
        // clean virtualenv is exists
        sh(script: "rm -rf ${workspace}/venv", returnStatus: true)

        openstack.setupOpenstackVirtualenv(openstackEnv, OS_VERSION)
        git.checkoutGitRepository(PACKER_TEMPLATES_REPO_NAME, PACKER_TEMPLATES_REPO_URL, PACKER_TEMPLATES_BRANCH)
        creds = common.getPasswordCredentials(OS_CREDENTIALS_ID)
      }

      stage("Build Instance") {
        dir("${workspace}/${PACKER_TEMPLATES_REPO_NAME}/${BUILD_OS}/") {
          withEnv(extra_vars + ["PATH=${env.PATH}:${workspace}/bin",
                                "PACKER_LOG_PATH=${workspace}/packer.log",
                                "PACKER_LOG=1",
                                "TMPDIR=${workspace}/tmp",
                                "IMAGE_NAME=${IMAGE_NAME}",
                                "OS_USERNAME=${creds.username}",
                                "OS_PASSWORD=${creds.password.toString()}"]) {
            if (PACKER_DEBUG.toBoolean()) {
              PACKER_ARGS = "${PACKER_ARGS} -debug"
            }

            sh "packer build -only=${BUILD_ONLY} ${PACKER_ARGS} -parallel=false template.json"

            def packerStatus = sh(script: "grep \"Some builds didn't complete successfully and had errors\" ${PACKER_LOG_PATH}", returnStatus: true)
            // grep returns 0 if find something
            if (packerStatus != 0) {
              common.infoMsg("Openstack instance complete")
            } else {
              throw new Exception("Packer build failed")
            }
          }
        }
      }

      stage("Publish image") {
        common.infoMsg("Saving image ${IMAGE_NAME}")
        rcFile = openstack.createOpenstackEnv(workspace, OS_URL, OS_CREDENTIALS_ID, OS_PROJECT, "default", "", "default", "2", "")

        common.retry(3, 5) {
          openstack.runOpenstackCommand("openstack image save --file ${IMAGE_NAME}.qcow2 ${IMAGE_NAME}", rcFile, openstackEnv)
        }
        sh "md5sum ${IMAGE_NAME}.qcow2 > ${IMAGE_NAME}.qcow2.md5"

        common.infoMsg("Uploading image ${IMAGE_NAME}")
        common.retry(3, 5) {
          uploadImageStatus = sh(script: "curl -f -T ${IMAGE_NAME}.qcow2 ${UPLOAD_URL}", returnStatus: true)
          if (uploadImageStatus != 0) {
            throw new Exception("Image upload failed")
          }
        }

        common.retry(3, 5) {
          uploadMd5Status = sh(script: "curl -f -T ${IMAGE_NAME}.qcow2.md5 ${UPLOAD_URL}", returnStatus: true)
          if (uploadMd5Status != 0) {
            throw new Exception("MD5 sum upload failed")
          }
        }
        currentBuild.description = "<a href='http://ci.mcp.mirantis.net:8085/images/${IMAGE_NAME}.qcow2'>${IMAGE_NAME}.qcow2</a>"
      }

    } catch (Throwable e) {
      // If there was an error or exception thrown, the build failed
      currentBuild.result = "FAILURE"
      throw e
    } finally {
      if (CLEANUP_AFTER) {
          dir(workspace) {
            sh "rm -rf ./*"
          }
      } else {
        common.infoMsg("Env has not been cleanup!")
        common.infoMsg("Packer private key:")
        dir("${workspace}/${PACKER_TEMPLATES_REPO_NAME}/${BUILD_OS}/") {
          sh "cat os_${BUILD_OS}.pem"
        }
      }
    }
  }
}
