/**
 * Generate cookiecutter cluster by individual products
 *
 * Expected parameters:
 *   COOKIECUTTER_TEMPLATE_CONTEXT      Context parameters for the template generation.
 *   SALT_MASTER_URL                    URL of Salt master
 *   SALT_MASTER_CREDENTIALS            Credentials to the Salt API
 *
 **/

import static groovy.json.JsonOutput.toJson

common = new com.mirantis.mk.Common()
python = new com.mirantis.mk.Python()
salt = new com.mirantis.mk.Salt()
ssh = new com.mirantis.mk.Ssh()

pepperEnv = "pepperEnv"

slaveNode = env.SLAVE_NODE ?: 'python&&docker'
model_job = 0

timeout(time: 2, unit: 'HOURS') {
    node(slaveNode) {
      try {
        def templateContext = readYaml text: COOKIECUTTER_TEMPLATE_CONTEXT
        def clusterName = templateContext.default_context.cluster_name
        def aioNodeHostname = templateContext.default_context.aio_node_hostname
        def aioInternalAddress = templateContext.default_context.aio_internal_address
        def drivetrainInternalAddress = templateContext.default_context.drivetrain_internal_address
        def artifact_tar_file = "${clusterName}.tar.gz"
        def rsyncUser = templateContext.default_context.rsync_user
        def masterIP = templateContext.default_context.drivetrain_external_address
        def masterUrl = "http://" + masterIP + ":6969"
        def rsyncLocation = templateContext.default_context.get("rsync_location", "/srv/salt/reclass/classes/cluster")
        def rsyncPath = rsyncUser + "@" + masterIP + ":" + rsyncLocation
        def rsyncSSHKey = templateContext.default_context.rsync_ssh_key
        def outputDirectory = env.WORKSPACE + "/"
        def rsyncKeyFile = outputDirectory + "publication_key"
        def outputDestination = outputDirectory + artifact_tar_file
        def outputCluster = outputDirectory + "/classes/cluster/" + clusterName
        currentBuild.description = "Cluster " + clusterName + " on " + masterIP

        stage("Generate AIO model") {
          model_job = build(job: 'generate-salt-model-separated-products',
                parameters: [
                  [$class: 'StringParameterValue', name: 'COOKIECUTTER_TEMPLATE_CONTEXT', value: COOKIECUTTER_TEMPLATE_CONTEXT ],
                  [$class: 'BooleanParameterValue', name: 'TEST_MODEL', value: false],
                ])
        }

        stage("Download artifact with model") {
          artifact_tar_url = "${env.JENKINS_URL}/job/generate-salt-model-separated-products/${model_job.number}/artifact/output-${clusterName}/${artifact_tar_file}"
          sh "wget --progress=dot:mega --auth-no-challenge -O ${outputDestination} '${artifact_tar_url}'"
          sh "tar -xzvf ${outputDestination}"
        }

        stage("Send model to Salt master node") {
          ssh.ensureKnownHosts(masterIP)
          writeFile(file: rsyncKeyFile, text: rsyncSSHKey)
          sh("chmod 600 ${rsyncKeyFile}")
          common.infoMsg("Copying cluster model to ${rsyncPath}")
          sh("rsync -r -e \"ssh -i ${rsyncKeyFile}\" ${outputCluster} ${rsyncPath}")
        }

        stage("Setup virtualenv for Pepper") {
          python.setupPepperVirtualenv(pepperEnv, masterUrl, SALT_MASTER_CREDENTIALS)
        }

        stage("Prepare AIO node"){
          tgt = "S@" + aioInternalAddress
          // Classify AIO node
          eventData = [:]
          eventData["node_control_ip"] = aioInternalAddress
          eventData["node_os"] = "xenial"
          eventData["node_master_ip"] = drivetrainInternalAddress
          eventData["node_hostname"] = aioNodeHostname
          eventData["node_cluster"] = clusterName
          eventJson = toJson(eventData)
          event = "salt-call event.send \"reclass/minion/classify\" \'" + eventJson + "\'"
          salt.cmdRun(pepperEnv, tgt, event)
          sleep(30)
          // Upgrade Salt minion
          salt.runSaltProcessStep(pepperEnv, tgt, 'pkg.install', "salt-minion")
          sleep(10)
          // Run core states on AIO node
          salt.fullRefresh(pepperEnv, '*')
          salt.enforceState(pepperEnv, tgt, 'linux')
          salt.enforceState(pepperEnv, tgt, 'salt')
          salt.enforceState(pepperEnv, tgt, 'openssh')
          salt.enforceState(pepperEnv, tgt, 'ntp')
          salt.enforceState(pepperEnv, tgt, 'rsyslog')
        }

        stage("Deploy Openstack") {
          build(job: 'deploy_openstack',
                parameters: [
                  [$class: 'StringParameterValue', name: 'SALT_MASTER_CREDENTIALS', value: SALT_MASTER_CREDENTIALS],
                  [$class: 'StringParameterValue', name: 'SALT_MASTER_URL', value: masterUrl],
                  [$class: 'StringParameterValue', name: 'STACK_INSTALL', value: 'openstack']
                ])
        }
      } catch (Throwable e) {
        currentBuild.result = "FAILURE"
        currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
        throw e
      } finally {
        stage('Clean workspace directories') {
          sh(script: 'find . -mindepth 1 -delete > /dev/null || true')
        }
      // common.sendNotification(currentBuild.result,"",["slack"])
      }
    }
}