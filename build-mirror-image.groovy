/**
 *
 * Update Salt environment pipeline
 *
 * Expected parameters:
 *   SALT_MASTER_URL            Salt API server location
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *   UPDATE_FORMULAS            Boolean switch for enforcing updating formulas
 */

// Load shared libs
def salt = new com.mirantis.mk.Salt()
def common = new com.mirantis.mk.Common()
def python = new com.mirantis.mk.Python()
def openstack = new com.mirantis.mk.Openstack()
def git = new com.mirantis.mk.Git()
def date = new Date()
def dateTime = date.format("ddMMyyyy-HHmmss")
def venvPepper = "venvPepper"
def venvS4cmd = "venvS4cmd"
def privateKey = ""
def floatingIP = ""
def openstackServer = ""
def rcFile = ""
def openstackEnv = ""
def serverStatus = ""

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
        def openstackVersion = "ocata"

        VM_IP_DELAY = VM_IP_DELAY as Integer
        VM_IP_RETRIES = VM_IP_RETRIES as Integer
        VM_CONNECT_DELAY = VM_CONNECT_DELAY as Integer
        VM_CONNECT_RETRIES = VM_CONNECT_RETRIES as Integer

        stage("Get templates"){

            if (!fileExists("${workspace}/tmp")) {
                sh "mkdir -p ${workspace}/tmp"
            }

            sh "wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/master/mirror-image/salt-bootstrap.sh"
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

            openstackServer = openstack.runOpenstackCommand("openstack server create --key-name mcp-offline-keypair-${dateTime} --availability-zone ${VM_AVAILABILITY_ZONE} --image ${VM_IMAGE} --flavor ${VM_FLAVOR} --nic net-id=${VM_NETWORK_ID},v4-fixed-ip=${VM_IP} --user-data mcp-offline-mirror/scripts/salt-bootstrap.sh mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
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
            salt.enforceState(venvPepper, '*apt*', ['docker.host'], true, false, null, false, -1, 2)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['docker run --restart always -d -p 5000:5000 --name registry registry:2'], null, true)
            salt.enforceState(venvPepper, '*apt*', ['docker.client.registry'], true, false, null, false, -1, 2)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['docker system prune --all --force'], null, true)
        }

        stage("Create Aptly"){
            common.infoMsg("Creating Aptly")
            salt.enforceState(venvPepper, '*apt*', ['aptly'], true, false, null, false, -1, 2)
            //TODO: Do it new way
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['aptly_mirror_update.sh -s -v', 'runas=aptly'], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['nohup aptly api serve --no-lock > /dev/null 2>&1 </dev/null &', 'runas=aptly'], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['aptly-publisher --timeout=1200 publish -v -c /etc/aptly-publisher.yaml --architectures amd64 --url http://127.0.0.1:8080 --recreate --force-overwrite', 'runas=aptly'], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/master/mirror-image/aptly/aptly-update.sh -O /srv/scripts/aptly-update.sh'], null, true)
            //NEW way
            //salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv", 'runas=aptly'], null, true)
            //salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-arf", 'runas=aptly'], null, true)
        }

        stage("Create Git mirror"){
            common.infoMsg("Creating Git mirror")
            salt.enforceState(venvPepper, '*apt*', ['git.server'], true, false, null, false, -1, 2)
        }

        stage("Create PyPi mirror"){
            common.infoMsg("Creating PyPi mirror")
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['pip install pip2pi'], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/master/mirror-image/pypi_mirror/requirements.txt -O /srv/pypi_mirror/requirements.txt'], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['pip2pi /srv/pypi_mirror/packages/ -r /srv/pypi_mirror/requirements.txt'], null, true)
        }

        stage("Create mirror of images"){
            common.infoMsg("Creating mirror of images")
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/master/mirror-image/images_mirror/images.txt -O /srv/images.txt'], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['wget https://raw.githubusercontent.com/Mirantis/mcp-common-scripts/master/mirror-image/images_mirror/update-images.sh -O /srv/scripts/update-images.sh'], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['chmod +x /srv/scripts/update-images.sh'], null, true)
            salt.runSaltProcessStep(venvPepper, '*apt*', 'cmd.run', ['/srv/scripts/update-images.sh -u http://ci.mcp.mirantis.net:8085/images'], null, true)
        }

        stage("Create instance snapshot"){
            openstack.runOpenstackCommand("openstack server stop mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
            retry(6, 30){
                serverStatus = openstack.runOpenstackCommand("openstack server show --format value -c status mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
                if(serverStatus != "SHUTOFF"){
                    throw new ResourceException("Instance is not ready for image create.")
                }
            }
            openstack.runOpenstackCommand("openstack server image create --name ${IMAGE_NAME}-${dateTime} --wait mcp-offline-mirror-${dateTime}", rcFile, openstackEnv)
        }

        stage("Publish image"){
            openstack.runOpenstackCommand("openstack image save --file ${IMAGE_NAME}-${dateTime} ${IMAGE_NAME}-${dateTime}", rcFile, openstackEnv)
            python.setupVirtualenv(venvS4cmd)
            python.runVirtualenvCommand(venvS4cmd, "pip install s4cmd")
            creds = common.getPasswordCredentials(AWS_CREDENTIALS_ID)
            python.runVirtualenvCommand(venvS4cmd, "python ./${venvS4cmd}/bin/s4cmd.py --access-key ${creds.username} --secret-key ${creds.password.toString()} --multipart-split-size=5368709120 put ${IMAGE_NAME}-${dateTime} s3://${AWS_S3_BUCKET_NAME}/${IMAGE_NAME}-${dateTime}")
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