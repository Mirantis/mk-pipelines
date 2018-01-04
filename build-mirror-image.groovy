/**
 *
 * Build mirror image pipeline
 *
 * Expected parameters:
 * CLUSTER_MODEL - An URL to the Reclass model for the mirror VM.
 * CLUSTER_NAME - Cluster name used in the above model.
 * IMAGE_NAME - Name of the result image.
 * OS_CREDENTIALS_ID - ID of credentials for OpenStack API stored in Jenkins.
 * OS_PROJECT - Project in OpenStack under the VM will be spawned.
 * OS_URL - Keystone auth endpoint of the OpenStack.
 * OS_VERSION - OpenStack version
 * SCRIPTS_REF - ref on the github to get the scripts from.
 * SALT_MASTER_CREDENTIALS - ID of credentials to be used to connect to the Salt API of the VM
 * UPLOAD_URL - URL of an WebDAV used to upload the image after creating.
 * VM_AVAILABILITY_ZONE - Availability zone in OpenStack in the VM will be spawned.
 * VM_CONNECT_RETRIES - Number of retries for SSH connection to the VM after it’s spawned after 8 minutes.
 * VM_CONNECT_DELAY - Delay between connect retries above.
 * VM_FLAVOR - Flavor to be used for VM in OpenStack.
 * VM_FLOATING_IP_POOL - Floating IP pool to be used to assign floating IP to the VM.
 * VM_IMAGE - Name of the image to be used for VM in OpenStack.
 * VM_IP - Static IP that is assigned to the VM which belongs to the network used.
 * VM_IP_RETRIES - Number of retries between tries to assign the floating IP to the VM.
 * VM_IP_DELAY - Delay between floating IP assign retries above.
 * VM_NETWORK_ID - ID of the network that VM connects to.
 *
 */

// Load shared libs
def salt = new com.mirantis.mk.Salt()
def common = new com.mirantis.mk.Common()
def python = new com.mirantis.mk.Python()
def openstack = new com.mirantis.mk.Openstack()
def date = new Date()
def dateTime = date.format("ddMMyyyy-HHmmss")
def venvPepper = "venvPepper"
def privateKey = ""
def floatingIP = ""
def openstackServer = ""
def rcFile = ""
def openstackEnv = ""
def serverStatus = ""
def uploadImageStatus = ""
def uploadMd5Status = ""

def retry(int times = 5, int delay = 0, Closure body) {
    int retries = 0
    def exceptions = []
    while(retries++ < times) {
        try {
            return body.call()
        } catch(e) {
            sleep(delay)
        }
    }
    currentBuild.result = "FAILURE"
    throw new Exception("Failed after $times retries")
}

node("python&&disk-xl") {
    try {
        def workspace = common.getWorkspace()
        rcFile = openstack.createOpenstackEnv(OS_URL, OS_CREDENTIALS_ID, OS_PROJECT, "default", "", "default", "2", "")
        openstackEnv = String.format("%s/venv", workspace)
        def openstackVersion = OS_VERSION

        VM_IP_DELAY = VM_IP_DELAY as Integer
        VM_IP_RETRIES = VM_IP_RETRIES as Integer
        VM_CONNECT_DELAY = VM_CONNECT_DELAY as Integer
        VM_CONNECT_RETRIES = VM_CONNECT_RETRIES as Integer

        stage("Get templates"){

            if (!fileExists("${workspace}/tmp")) {
                sh "mkdir -p ${workspace}/tmp"
            }

            sh "wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/${SCRIPTS_REF}/mirror-image/salt-bootstrap.sh"
            openstack.setupOpenstackVirtualenv(openstackEnv, openstackVersion)
        }

        stage("Spawn Instance"){
            privateKey = openstack.runOpenstackCommand("openstack keypair create mcp-offline-keypair-${dateTime}", rcFile, openstackEnv)

            common.infoMsg(privateKey)
            sh "echo '${privateKey}' > id_rsa;chmod 600 id_rsa"

            floatingIP = openstack.runOpenstackCommand("openstack ip floating create --format value -c floating_ip_address ${VM_FLOATING_IP_POOL}", rcFile, openstackEnv)

            withEnv(["CLUSTER_NAME=${CLUSTER_NAME}", "CLUSTER_MODEL=${CLUSTER_MODEL}"]) {
                sh "envsubst < salt-bootstrap.sh > salt-bootstrap.sh.temp;mv salt-bootstrap.sh.temp salt-bootstrap.sh; cat salt-bootstrap.sh"
            }

            openstackServer = openstack.runOpenstackCommand("openstack server create --key-name mcp-offline-keypair-${dateTime} --availability-zone ${VM_AVAILABILITY_ZONE} --image ${VM_IMAGE} --flavor ${VM_FLAVOR} --nic net-id=${VM_NETWORK_ID},v4-fixed-ip=${VM_IP} --user-data salt-bootstrap.sh mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
            sleep(60)

            retry(VM_IP_RETRIES, VM_IP_DELAY){
                openstack.runOpenstackCommand("openstack ip floating add ${floatingIP} mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
            }

            sleep(500)

            retry(VM_CONNECT_RETRIES, VM_CONNECT_DELAY){
                sh "scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i id_rsa root@${floatingIP}:/srv/initComplete ./"
            }

            python.setupPepperVirtualenv(venvPepper, "http://${floatingIP}:6969", SALT_MASTER_CREDENTIALS)
        }
        stage("Prepare instance"){
            salt.runSaltProcessStep(venvPepper, '*apt*', 'saltutil.refresh_pillar', [], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'saltutil.sync_all', [], null, true)
            salt.enforceState(venvPepper, '*apt*', ['salt'], true, false, null, false, -1, 2)
            salt.enforceState(venvPepper, '*apt*', ['linux'], true, false, null, false, -1, 2)
            salt.enforceState(venvPepper, '*apt*', ['nginx'], true, false, null, false, -1, 2)
        }

        stage("Create Docker Registry"){
            common.infoMsg("Creating Docker Registry")
            salt.enforceState(venvPepper, '*apt*', ["docker.host"], true, false, null, false, -1, 2)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["docker run --restart always -d -p 5000:5000 --name registry registry:2"], null, true)
            salt.enforceState(venvPepper, '*apt*', ["docker.client.registry"], true, false, null, false, -1, 2)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["docker system prune --all --force"], null, true)
        }

        stage("Create Aptly"){
            common.infoMsg("Creating Aptly")
            salt.enforceState(venvPepper, '*apt*', ['aptly'], true, false, null, false, -1, 2)
            //TODO: Do it new way
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["aptly_mirror_update.sh -s -v", "runas=aptly"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["nohup aptly api serve --no-lock > /dev/null 2>&1 </dev/null &", "runas=aptly"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["aptly-publisher --timeout=1200 publish -v -c /etc/aptly-publisher.yaml --architectures amd64 --url http://127.0.0.1:8080 --recreate --force-overwrite", "runas=aptly"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["aptly db cleanup", "runas=aptly"], null, true)
            //NEW way
            //salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv", "runas=aptly"], null, true)
            //salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-acrfv", "runas=aptly"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/${SCRIPTS_REF}/mirror-image/aptly/aptly-update.sh -O /srv/scripts/aptly-update.sh"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["chmod +x /srv/scripts/aptly-update.sh"], null, true)
        }

        stage("Create Debmirrors"){
            common.infoMsg("Creating Debmirrors")
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/${SCRIPTS_REF}/mirror-image/debmirror.sh -O /srv/scripts/debmirror.sh"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["chmod +x /srv/scripts/debmirror.sh"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["export MCP_VERSION='${MCP_VERSION}';/srv/scripts/debmirror.sh"], null, true)
        }

        stage("Create Git mirror"){
            common.infoMsg("Creating Git mirror")
            salt.enforceState(venvPepper, '*apt*', ['git.server'], true, false, null, false, -1, 2)
        }

        stage("Create PyPi mirror"){
            common.infoMsg("Creating PyPi mirror")
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["pip install pip2pi"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/${SCRIPTS_REF}/mirror-image/pypi_mirror/requirements.txt -O /srv/pypi_mirror/requirements.txt"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["pip2pi /srv/pypi_mirror/packages/ -r /srv/pypi_mirror/requirements.txt"], null, true)
        }

        stage("Create mirror of images"){
            common.infoMsg("Creating mirror of images")
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/${SCRIPTS_REF}/mirror-image/images_mirror/images.txt -O /srv/images.txt"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/${SCRIPTS_REF}/mirror-image/images_mirror/update-images.sh -O /srv/scripts/update-images.sh"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["chmod +x /srv/scripts/update-images.sh"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["/srv/scripts/update-images.sh -u http://ci.mcp.mirantis.net:8085/images"], null, true)
        }

        stage("Create instance snapshot"){
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["rm -rf /var/lib/cloud/sem/* /var/lib/cloud/instance /var/lib/cloud/instances/*"], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ["cloud-init init"], null, true)

            retry(3, 5){
                openstack.runOpenstackCommand("openstack server stop mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
            }

            retry(6, 30){
                serverStatus = openstack.runOpenstackCommand("openstack server show --format value -c status mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
                if(serverStatus != "SHUTOFF"){
                    throw new ResourceException("Instance is not ready for image create.")
                }
            }
            retry(3, 5){
                openstack.runOpenstackCommand("openstack server image create --name ${IMAGE_NAME}-${dateTime} --wait mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
            }
        }

        stage("Publish image"){
            common.infoMsg("Saving image ${IMAGE_NAME}-${dateTime}")
            retry(3, 5){
                openstack.runOpenstackCommand("openstack image save --file ${IMAGE_NAME}-${dateTime} ${IMAGE_NAME}-${dateTime}", rcFile, openstackEnv)
            }
            sh "md5sum ${IMAGE_NAME}-${dateTime} > ${IMAGE_NAME}-${dateTime}.md5"

            common.infoMsg("Uploading image ${IMAGE_NAME}-${dateTime}")
            retry(3, 5){
                uploadImageStatus = sh(script: "curl -f -T ${IMAGE_NAME}-${dateTime} ${UPLOAD_URL}", returnStatus: true)
                if(uploadImageStatus!=0){
                    throw new Exception("Image upload failed")
                }
            }
            retry(3, 5){
                uploadMd5Status = sh(script: "curl -f -T ${IMAGE_NAME}-${dateTime}.md5 ${UPLOAD_URL}", returnStatus: true)
                if(uploadMd5Status != 0){
                    throw new Exception("MD5 sum upload failed")
                }
            }
        }

    } catch (Throwable e) {
        // If there was an error or exception thrown, the build failed
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        stage("Cleanup"){
            if(openstackServer != ""){
                openstack.runOpenstackCommand("openstack ip floating remove ${floatingIP} mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
                openstack.runOpenstackCommand("openstack server delete mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
            }
            if(privateKey != ""){
                openstack.runOpenstackCommand("openstack keypair delete mcp-offline-keypair-${dateTime}", rcFile, openstackEnv)
            }
            sh "rm -rf ./*"
        }
    }
}