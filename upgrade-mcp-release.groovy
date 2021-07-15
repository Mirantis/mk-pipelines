/**
 *
 * Update Salt environment pipeline
 *
 * Expected parameters:
 *   TARGET_MCP_VERSION         Version of MCP to upgrade to
 *   GIT_REFSPEC                Git repo ref to be used
 *   DRIVE_TRAIN_PARAMS         Yaml, DriveTrain releated params:
 *     SALT_MASTER_URL            Salt API server location
 *     SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *     BATCH_SIZE                 Use batch sizing during upgrade for large envs
 *     UPGRADE_SALTSTACK          Upgrade SaltStack packages to new version.
 *     UPDATE_CLUSTER_MODEL       Update MCP version parameter in cluster model
 *     UPDATE_PIPELINES           Update pipeline repositories on Gerrit
 *     UPDATE_LOCAL_REPOS         Update local repositories
 *     OS_UPGRADE                 Run apt-get upgrade on Drivetrain nodes
 *     OS_DIST_UPGRADE            Run apt-get dist-upgrade on Drivetrain nodes and reboot to apply changes
 *     APPLY_MODEL_WORKAROUNDS    Whether to apply cluster model workarounds from the pipeline
 */

salt = new com.mirantis.mk.Salt()
common = new com.mirantis.mk.Common()
python = new com.mirantis.mk.Python()
jenkinsUtils = new com.mirantis.mk.JenkinsUtils()
def pipelineTimeout = 12
venvPepper = "venvPepper"
workspace = ""
def saltMastURL = ''
def saltMastCreds = ''
def packageUpgradeMode = ''
batchSize = ''

def fullRefreshOneByOne(venvPepper, minions) {
    for (minion in minions) {
        salt.runSaltProcessStep(venvPepper, minion, 'saltutil.refresh_pillar', [], null, true, 60)
        salt.runSaltProcessStep(venvPepper, minion, 'saltutil.refresh_grains', [], null, true, 60)
        salt.runSaltProcessStep(venvPepper, minion, 'saltutil.sync_all', [], null, true, 180)
    }
}

def triggerMirrorJob(String jobName, String reclassSystemBranch) {
    params = jenkinsUtils.getJobParameters(jobName)
    try {
        build job: jobName, parameters: [
            [$class: 'StringParameterValue', name: 'BRANCHES', value: params.get('BRANCHES')],
            [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: params.get('CREDENTIALS_ID')],
            [$class: 'StringParameterValue', name: 'SOURCE_URL', value: params.get('SOURCE_URL')],
            [$class: 'StringParameterValue', name: 'TARGET_URL', value: params.get('TARGET_URL')]
        ]
    } catch (Exception updateErr) {
        common.warningMsg(updateErr)
        common.warningMsg('Attempt to update git repo in failsafe manner')
        build job: jobName, parameters: [
            [$class: 'StringParameterValue', name: 'BRANCHES', value: reclassSystemBranch.replace('origin/', '')],
            [$class: 'StringParameterValue', name: 'CREDENTIALS_ID', value: params.get('CREDENTIALS_ID')],
            [$class: 'StringParameterValue', name: 'SOURCE_URL', value: params.get('SOURCE_URL')],
            [$class: 'StringParameterValue', name: 'TARGET_URL', value: params.get('TARGET_URL')]
        ]
    }
}

def updateSaltStack(target, pkgs) {
    salt.cmdRun(venvPepper, 'I@salt:master', "salt -C '${target}' --async pkg.install force_yes=True pkgs='$pkgs'")
    // can't use same function from pipeline lib, as at the moment of running upgrade pipeline Jenkins
    // still using pipeline lib from current old mcp-version

    // sleep to make sure package update started, otherwise checks will pass on still running old instance
    sleep(120)

    common.retry(20, 60) {
        salt.minionsReachable(venvPepper, 'I@salt:master', '*')
        def running = salt.runSaltProcessStep(venvPepper, target, 'saltutil.running', [], null, true, 5)
        for (value in running.get("return")[0].values()) {
            if (value != []) {
                throw new Exception("Not all salt-minions are ready for execution")
            }
        }
    }

    def saltVersion = salt.getPillar(venvPepper, 'I@salt:master', '_param:salt_version').get('return')[0].values()[0]
    def saltMinionVersions = salt.cmdRun(venvPepper, target, "apt-cache policy salt-common |  awk '/Installed/ && /$saltVersion/'").get("return")
    def saltMinionVersion = ""

    for (minion in saltMinionVersions[0].keySet()) {
        saltMinionVersion = saltMinionVersions[0].get(minion).replace("Salt command execution success", "").trim()
        if (saltMinionVersion == "") {
            error("Installed version of Salt on $minion doesn't match specified version in the model.")
        }
    }
}

def getWorkerThreads(saltId) {
    if (env.getEnvironment().containsKey('SALT_MASTER_OPT_WORKER_THREADS')) {
        return env['SALT_MASTER_OPT_WORKER_THREADS'].toString()
    }
    def threads = salt.cmdRun(saltId, "I@salt:master", "cat /etc/salt/master.d/master.conf | grep worker_threads | cut -f 2 -d ':'", true, null, true)
    return threads['return'][0].values()[0].replaceAll('Salt command execution success','').trim()
}

def wa29352(String cname) {
    // WA for PROD-29352. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/12/openssh/client/root.yml
    // Default soft-param has been removed, what now makes not possible to render some old env's.
    // Like fix, we found copy-paste already generated key from backups, to secrets.yml with correct key name
    def wa29352ClassName = 'cluster.' + cname + '.infra.secrets_root_wa29352'
    def wa29352File = "/srv/salt/reclass/classes/cluster/${cname}/infra/secrets_root_wa29352.yml"
    def wa29352SecretsFile = "/srv/salt/reclass/classes/cluster/${cname}/infra/secrets.yml"
    def _tempFile = '/tmp/wa29352_' + UUID.randomUUID().toString().take(8)
    try {
        salt.cmdRun(venvPepper, 'I@salt:master', "! grep -qi root_private_key: ${wa29352SecretsFile}", true, null, false)
        salt.cmdRun(venvPepper, 'I@salt:master', "test ! -f ${wa29352File}", true, null, false)
    }
    catch (Exception ex) {
        common.infoMsg('Work-around for PROD-29352 already applied, nothing todo')
        return
    }
    def rKeysDict = [
        'parameters': [
            '_param': [
                'root_private_key': salt.getPillar(venvPepper, 'I@salt:master', '_param:root_private_key').get('return')[0].values()[0].trim(),
                'root_public_key' : '',
            ]
        ]
    ]
    // save root key,and generate public one from it
    writeFile file: _tempFile, text: rKeysDict['parameters']['_param']['root_private_key'].toString().trim()
    sh('chmod 0600 ' + _tempFile)
    rKeysDict['parameters']['_param']['root_public_key'] = sh(script: "ssh-keygen -q -y -f ${_tempFile}", returnStdout: true).trim()
    sh('rm -fv ' + _tempFile)
    writeYaml file: _tempFile, data: rKeysDict
    def yamlData = sh(script: "cat ${_tempFile} | base64", returnStdout: true).trim()
    salt.cmdRun(venvPepper, 'I@salt:master', "echo '${yamlData}' | base64 -d  > ${wa29352File}", false, null, false)
    common.infoMsg("Add $wa29352ClassName class into secrets.yml")

    // Add 'classes:' directive
    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
        "grep -q 'classes:' infra/secrets.yml || sed -i '1iclasses:' infra/secrets.yml")

    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
        "grep -q '${wa29352ClassName}' infra/secrets.yml || sed -i '/classes:/ a - $wa29352ClassName' infra/secrets.yml")
    sh('rm -fv ' + _tempFile)
    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && git status && " +
            "git add ${wa29352File} && git add -u && git commit --allow-empty -m 'Cluster model updated with WA for PROD-29352. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/ at ${common.getDatetime()}' ")
    common.infoMsg('Work-around for PROD-29352 successfully applied')
}

def wa29155(ArrayList saltMinions, String cname) {
    // WA for PROD-29155. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/
    // CHeck for existence cmp nodes, and try to render it. Is failed, apply ssh-key wa
    def patched = false
    def wa29155ClassName = 'cluster.' + cname + '.infra.secrets_nova_wa29155'
    def wa29155File = "/srv/salt/reclass/classes/cluster/${cname}/infra/secrets_nova_wa29155.yml"

    try {
        salt.cmdRun(venvPepper, 'I@salt:master', "test ! -f ${wa29155File}", true, null, false)
        def patch_required = false
        for (String minion in saltMinions) {
            def nova_key = salt.getPillar(venvPepper, minion, '_param:nova_compute_ssh_private').get("return")[0].values()[0]
            if (nova_key == '' || nova_key == 'null' || nova_key == null) {
                patch_required = true
                break // no exception, proceeding to apply the patch
            }
        }
        if (!patch_required) {
            error('No need to apply work-around for PROD-29155')
        }
    }
    catch (Exception ex) {
        return
    }
    salt.fullRefresh(venvPepper, 'I@salt:master')
    for (String minion in saltMinions) {
        // First attempt, second will be performed in next validateReclassModel() stages
        try {
            salt.cmdRun(venvPepper, 'I@salt:master', "reclass -n ${minion}", true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
        } catch (Exception e) {
            common.errorMsg(e.toString())
            if (patched) {
                error("Node: ${minion} failed to render after reclass-system upgrade! WA29155 probably didn't help.")
            }
            common.warningMsg('Perform: Attempt to apply WA for PROD-29155\n' +
                'See https://gerrit.mcp.mirantis.com/#/c/37932/ for more info')
            common.warningMsg('WA-PROD-29155 Generating new ssh key at master node')
            def _tempFile = "/tmp/nova_wa29155_" + UUID.randomUUID().toString().take(8)
            common.infoMsg('Perform: generation NEW ssh-private key for nova-compute')
            salt.cmdRun(venvPepper, 'I@salt:master', "ssh-keygen -f ${_tempFile} -N '' -q")
            def _pub_k = salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'cmd.run', "cat ${_tempFile}.pub").get('return')[0].values()[0].trim()
            def _priv_k = salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'cmd.run', "cat ${_tempFile}").get('return')[0].values()[0].trim()
            salt.cmdRun(venvPepper, 'I@salt:master', "rm -fv ${_tempFile}", false, null, false)
            def novaKeysDict = [
                "parameters": [
                    "_param": [
                        "nova_compute_ssh_private": _priv_k,
                        "nova_compute_ssh_public" : _pub_k
                    ]
                ]
            ]
            writeYaml file: _tempFile, data: novaKeysDict
            def yamlData = sh(script: "cat ${_tempFile} | base64", returnStdout: true).trim()
            salt.cmdRun(venvPepper, 'I@salt:master', "echo '${yamlData}' | base64 -d  > ${wa29155File}", false, null, false)
            common.infoMsg("Add $wa29155ClassName class into secrets.yml")

            // Add 'classes:' directive
            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
                "grep -q 'classes:' infra/secrets.yml || sed -i '1iclasses:' infra/secrets.yml")

            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && " +
                "grep -q '${wa29155ClassName}' infra/secrets.yml || sed -i '/classes:/ a - $wa29155ClassName' infra/secrets.yml")
            salt.fullRefresh(venvPepper, 'I@salt:master')
            salt.runSaltProcessStep(venvPepper, saltMinions, 'saltutil.refresh_pillar', [], null, true, 60)
            patched = true
        }
    }
    if (patched) {
        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cname && git status && " +
            "git add ${wa29155File} && git add -u && git commit --allow-empty -m 'Cluster model updated with WA for PROD-29155. Issue cause due patch https://gerrit.mcp.mirantis.com/#/c/37932/ at ${common.getDatetime()}' ")
        common.infoMsg('Work-around for PROD-29155 successfully applied')
    }

}

def wa32284(String cluster_name) {
    def clientGluster = salt.getPillar(venvPepper, 'I@salt:master', "glusterfs:client:enabled").get("return")[0].values()[0]
    def pkiGluster = salt.getPillar(venvPepper, 'I@salt:master', "glusterfs:client:volumes:salt_pki").get("return")[0].values()[0]
    def nginxEnabledAtMaster = salt.getPillar(venvPepper, 'I@salt:master', 'nginx:server:enabled').get('return')[0].values()[0]
    if (nginxEnabledAtMaster.toString().toLowerCase() == 'true' && clientGluster.toString().toLowerCase() == 'true' && pkiGluster) {
        def nginxRequires = salt.getPillar(venvPepper, 'I@salt:master', 'nginx:server:wait_for_service').get('return')[0].values()[0]
        if (nginxRequires.isEmpty()) {
            def nginxRequiresClassName = "cluster.${cluster_name}.infra.config.nginx_requires_wa32284"
            def nginxRequiresClassFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/infra/config/nginx_requires_wa32284.yml"
            def nginxRequiresBlock = ['parameters': ['nginx': ['server': ['wait_for_service': ['srv-salt-pki.mount'] ] ] ] ]
            def _tempFile = '/tmp/wa32284_' + UUID.randomUUID().toString().take(8)
            writeYaml file: _tempFile , data: nginxRequiresBlock
            def nginxRequiresBlockString = sh(script: "cat ${_tempFile}", returnStdout: true).trim()
            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/${cluster_name} && " +
                "sed -i '/^parameters:/i - ${nginxRequiresClassName}' infra/config/init.yml")
            salt.cmdRun(venvPepper, 'I@salt:master', "echo '${nginxRequiresBlockString}'  > ${nginxRequiresClassFile}", false, null, false)
            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/${cluster_name} && git status && git add ${nginxRequiresClassFile}")
        }
    }
}

def check_34406(String cluster_name) {
    def sphinxpasswordPillar = salt.getPillar(venvPepper, 'I@salt:master', '_param:sphinx_proxy_password_generated').get("return")[0].values()[0]
    if (sphinxpasswordPillar == '' || sphinxpasswordPillar == 'null' || sphinxpasswordPillar == null) {
        error('Sphinx password is not defined.\n' +
        'See https://docs.mirantis.com/mcp/q4-18/mcp-release-notes/mu/mu-9/mu-9-addressed/mu-9-dtrain/mu-9-dt-manual.html#i-34406 for more info')
    }
}

def check_35705(String cluster_name) {
    def galeracheckpasswordPillar = salt.getPillar(venvPepper, 'I@salt:master', '_param:galera_clustercheck_password').get("return")[0].values()[0]
    if (galeracheckpasswordPillar == '' || galeracheckpasswordPillar == 'null' || galeracheckpasswordPillar == null) {
        error('Galera clustercheck password is not defined.\n' +
        'See https://docs.mirantis.com/mcp/q4-18/mcp-release-notes/mu/mu-12/mu-12-addressed/mu-12-dtrain/mu-12-dt-manual.html#improper-operation-of-galera-ha for more info')
    }
}

def check_35884(String cluster_name) {
    if (salt.getMinions(venvPepper, 'I@prometheus:alerta or I@prometheus:alertmanager')) {
        def alertaApiKeyGenPillar = salt.getPillar(venvPepper, 'I@salt:master', '_param:alerta_admin_api_key_generated').get("return")[0].values()[0]
        def alertaApiKeyPillar = salt.getPillar(venvPepper, 'I@prometheus:alerta or I@prometheus:alertmanager', '_param:alerta_admin_key').get("return")[0].values()[0]

        if (alertaApiKeyGenPillar == '' || alertaApiKeyGenPillar == 'null' || alertaApiKeyGenPillar == null || alertaApiKeyPillar == '' || alertaApiKeyPillar == 'null' || alertaApiKeyPillar == null) {
            error('Alerta admin API key not defined.\n' +
            'See https://docs.mirantis.com/mcp/q4-18/mcp-release-notes/mu/mu-12/mu-12-addressed/mu-12-dtrain/mu-12-dt-manual.html#i-35884 for more info')
        }
    }
}

// ceph cluster class ordering for radosgw
def check_36461(String cluster_name){
    if (!salt.testTarget(venvPepper, 'I@ceph:radosgw')) {
        return
    }
    def clusterModelPath = "/srv/salt/reclass/classes/cluster/${cluster_name}"
    def checkFile = "${clusterModelPath}/ceph/rgw.yml"
    def saltTarget = "I@salt:master"
    try {
        salt.cmdRun(venvPepper, saltTarget, "test -f ${checkFile}")
    }
    catch (Exception e) {
        common.warningMsg("Unable to check ordering of RadosGW imports, file ${checkFile} not found, skipping")
        return
    }
    def fileContent = salt.cmdRun(venvPepper, saltTarget, "cat ${checkFile}").get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
    def yamlData = readYaml text: fileContent
    def infraClassImport = "cluster.${cluster_name}.infra"
    def cephClassImport = "cluster.${cluster_name}.ceph"
    def cephCommonClassImport = "cluster.${cluster_name}.ceph.common"
    def infraClassFound = false
    def importErrorDetected = false
    def importErrorMessage = """Ceph classes in '${checkFile}' are used in wrong order! Please reorder it:
'${infraClassImport}' should be placed before '${cephClassImport}' and '${cephCommonClassImport}'.
For additional information please see %INSERT_DOC_LINK_HERE%"""
    for (yamlClass in yamlData.classes) {
        switch(yamlClass){
          case infraClassImport:
            infraClassFound = true;
            break;
          case cephClassImport:
            if (!infraClassFound) {
              importErrorDetected = true
            };
            break;
          case cephCommonClassImport:
            if (!infraClassFound) {
              importErrorDetected = true
            };
            break;
        }
    }
    if (importErrorDetected) {
        common.errorMsg(importErrorMessage)
        error(importErrorMessage)
    }
}

def wa32182(String cluster_name) {
    if (salt.testTarget(venvPepper, 'I@opencontrail:control or I@opencontrail:collector')) {
        def clusterModelPath = "/srv/salt/reclass/classes/cluster/${cluster_name}"
        def fixFile = "${clusterModelPath}/opencontrail/common_wa32182.yml"
        def usualFile = "${clusterModelPath}/opencontrail/common.yml"
        def fixFileContent = "classes:\n- system.opencontrail.common\n"
        salt.cmdRun(venvPepper, 'I@salt:master', "test -f ${fixFile} -o -f ${usualFile} || echo '${fixFileContent}' > ${fixFile}")
        def contrailFiles = ['opencontrail/analytics.yml', 'opencontrail/control.yml', 'openstack/compute/init.yml']
        if (salt.testTarget(venvPepper, "I@kubernetes:master")) {
            contrailFiles.add('kubernetes/compute.yml')
        }
        for (String contrailFile in contrailFiles) {
            contrailFile = "${clusterModelPath}/${contrailFile}"
            def containsFix = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- cluster\\.${cluster_name}\\.opencontrail\\.common(_wa32182)?\$' ${contrailFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
            if (containsFix) {
                continue
            } else {
                salt.cmdRun(venvPepper, 'I@salt:master', "grep -q -E '^parameters:' ${contrailFile} && sed -i '/^parameters:/i - cluster.${cluster_name}.opencontrail.common_wa32182' ${contrailFile} || " +
                        "echo '- cluster.${cluster_name}.opencontrail.common_wa32182' >> ${contrailFile}")
            }
        }
        salt.cmdRun(venvPepper, 'I@salt:master', "test -f ${fixFile} && cd ${clusterModelPath} && git status && git add ${fixFile} || true")
    }
}

def wa33867(String cluster_name) {
    if (salt.testTarget(venvPepper, 'I@opencontrail:control or I@opencontrail:collector')) {
        def contrailControlFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/opencontrail/control.yml"
        def line = salt.cmdRun(venvPepper, 'I@salt:master', "awk '/^- cluster.${cluster_name}.infra.backup.client_zookeeper/ {getline; print \$0}' ${contrailControlFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
        if (line == "- cluster.${cluster_name}.infra") {
            salt.cmdRun(venvPepper, 'I@salt:master', "sed -i '/^- cluster.${cluster_name}.infra\$/d' ${contrailControlFile}")
            salt.cmdRun(venvPepper, 'I@salt:master', "sed -i '/^- cluster.${cluster_name}.infra.backup.client_zookeeper\$/i - cluster.${cluster_name}.infra' ${contrailControlFile}")
        }
    }
}

def wa33771(String cluster_name) {
    if (salt.getMinions(venvPepper, 'I@_param:openstack_node_role and I@apache:server')) {
        def octaviaEnabled = salt.getMinions(venvPepper, 'I@octavia:api:enabled')
        def octaviaWSGI = salt.getMinions(venvPepper, 'I@apache:server:site:octavia_api')
        if (octaviaEnabled && !octaviaWSGI) {
            def openstackControl = "/srv/salt/reclass/classes/cluster/${cluster_name}/openstack/control.yml"
            def octaviaFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/openstack/octavia_wa33771.yml"
            def octaviaContext = [
                    'classes'   : ['system.apache.server.site.octavia'],
                    'parameters': [
                            '_param': ['apache_octavia_api_address': '${_param:cluster_local_address}'],
                    ]
            ]
            def openstackHTTPSEnabled = salt.getPillar(venvPepper, 'I@salt:master', "_param:cluster_internal_protocol").get("return")[0].values()[0]
            if (openstackHTTPSEnabled == 'https') {
                octaviaContext['parameters'] << ['apache': ['server': ['site': ['apache_proxy_openstack_api_octavia': ['enabled': false]]]]]
            }
            def _tempFile = '/tmp/wa33771' + UUID.randomUUID().toString().take(8)
            writeYaml file: _tempFile, data: octaviaContext
            def octaviaFileContent = sh(script: "cat ${_tempFile} | base64", returnStdout: true).trim()
            salt.cmdRun(venvPepper, 'I@salt:master', "sed -i '/^parameters:/i - cluster.${cluster_name}.openstack.octavia_wa33771' ${openstackControl}")
            salt.cmdRun(venvPepper, 'I@salt:master', "echo '${octaviaFileContent}' | base64 -d > ${octaviaFile}", false, null, false)
            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/${cluster_name} && git status && git add ${octaviaFile}")
        }
    } else {
        common.warningMsg("Apache server is not defined on controller nodes. Skipping Octavia WSGI workaround");
    }
}

def wa33930_33931(String cluster_name) {
    def openstackControlFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/openstack/control.yml"
    def fixName = 'clients_common_wa33930_33931'
    def fixFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/openstack/${fixName}.yml"
    def containsFix = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- cluster\\.${cluster_name}\\.openstack\\.${fixName}\$' ${openstackControlFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
    if (! containsFix) {
        def fixContext = [ 'classes': [ ] ]
        def novaControllerNodes = salt.getMinions(venvPepper, 'I@nova:controller')
        for (novaController in novaControllerNodes) {
            def novaClientPillar = salt.getPillar(venvPepper, novaController, "nova:client").get("return")[0].values()[0]
            if (novaClientPillar == '' || novaClientPillar == 'null' || novaClientPillar == null) {
                fixContext['classes'] << 'service.nova.client'
                break
            }
        }
        def glanceServerNodes = salt.getMinions(venvPepper, 'I@glance:server')
        for (glanceServer in glanceServerNodes) {
            def glanceClientPillar = salt.getPillar(venvPepper, glanceServer, "glance:client").get("return")[0].values()[0]
            if (glanceClientPillar == '' || glanceClientPillar == 'null' || glanceClientPillar == null) {
                fixContext['classes'] << 'service.glance.client'
                break
            }
        }
        def neutronServerNodes = salt.getMinions(venvPepper, 'I@neutron:server')
        for (neutronServer in neutronServerNodes) {
            def neutronServerPillar = salt.getPillar(venvPepper, neutronServer, "neutron:client").get("return")[0].values()[0]
            if (neutronServerPillar == '' || neutronServerPillar == 'null' || neutronServerPillar == null) {
                fixContext['classes'] << 'service.neutron.client'
                break
            }
        }
        if (salt.getMinions(venvPepper, 'I@manila:api:enabled')) {
            def manilaApiNodes = salt.getMinions(venvPepper, 'I@manila:api')
            for (manilaNode in manilaApiNodes) {
                def manilaNodePillar = salt.getPillar(venvPepper, manilaNode, "manila:client").get("return")[0].values()[0]
                if (manilaNodePillar == '' || manilaNodePillar == 'null' || manilaNodePillar == null) {
                    fixContext['classes'] << 'service.manila.client'
                    break
                }
            }
        }
        if (salt.getMinions(venvPepper, 'I@ironic:api:enabled')) {
            def ironicApiNodes = salt.getMinions(venvPepper, 'I@ironic:api')
            for (ironicNode in ironicApiNodes) {
                def ironicNodePillar = salt.getPillar(venvPepper, ironicNode, "ironic:client").get("return")[0].values()[0]
                if (ironicNodePillar == '' || ironicNodePillar == 'null' || ironicNodePillar == null) {
                    fixContext['classes'] << 'service.ironic.client'
                    break
                }
            }
        }
        if (salt.getMinions(venvPepper, 'I@gnocchi:server:enabled')) {
            def gnocchiServerNodes = salt.getMinions(venvPepper, 'I@gnocchi:server')
            for (gnocchiNode in gnocchiServerNodes) {
                def gnocchiNodePillar = salt.getPillar(venvPepper, gnocchiNode, "gnocchi:client").get("return")[0].values()[0]
                if (gnocchiNodePillar == '' || gnocchiNodePillar == 'null' || gnocchiNodePillar == null) {
                    fixContext['classes'] << 'service.gnocchi.client'
                    break
                }
            }
        }

        if (salt.getMinions(venvPepper, 'I@barbican:server:enabled')) {
            def barbicanServerNodes = salt.getMinions(venvPepper, 'I@barbican:server')
            for (barbicanNode in barbicanServerNodes) {
                def barbicanNodePillar = salt.getPillar(venvPepper, barbicanNode, "barbican:client").get("return")[0].values()[0]
                if (barbicanNodePillar == '' || barbicanNodePillar == 'null' || barbicanNodePillar == null) {
                    fixContext['classes'] << 'service.barbican.client.single'
                    break
                }
            }
        }
        if (fixContext['classes'] != []) {
            def _tempFile = '/tmp/wa33930_33931' + UUID.randomUUID().toString().take(8)
            writeYaml file: _tempFile, data: fixContext
            def fixFileContent = sh(script: "cat ${_tempFile} | base64", returnStdout: true).trim()
            salt.cmdRun(venvPepper, 'I@salt:master', "echo '${fixFileContent}' | base64 -d > ${fixFile}", false, null, false)
            salt.cmdRun(venvPepper, 'I@salt:master', "sed -i '/^parameters:/i - cluster.${cluster_name}.openstack.${fixName}' ${openstackControlFile}")
            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/${cluster_name} && git status && git add ${fixFile}")
        }
    }
}

def wa34245(cluster_name) {
    def infraInitFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/infra/init.yml"
    def fixName = 'hosts_wa34245'
    def fixFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/infra/${fixName}.yml"
    if (salt.testTarget(venvPepper, 'I@keystone:server')) {
        def fixApplied = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- cluster.${cluster_name}.infra.${fixName}\$' ${infraInitFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
        if (!fixApplied) {
            def fixFileContent = []
            def containsFix = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- system\\.linux\\.network\\.hosts\\.openstack\$' ${infraInitFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
            if (!containsFix) {
                fixFileContent << '- system.linux.network.hosts.openstack'
            }
            if (salt.testTarget(venvPepper, 'I@gnocchi:server')) {
                containsFix = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- system\\.linux\\.network\\.hosts\\.openstack\\.telemetry\$' ${infraInitFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
                if (!containsFix) {
                    fixFileContent << '- system.linux.network.hosts.openstack.telemetry'
                }
            }
            if (salt.testTarget(venvPepper, 'I@manila:api')) {
                containsFix = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- system\\.linux\\.network\\.hosts\\.openstack\\.share\$' ${infraInitFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
                if (!containsFix) {
                    fixFileContent << '- system.linux.network.hosts.openstack.share'
                }
            }
            if (salt.testTarget(venvPepper, 'I@barbican:server')) {
                containsFix = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- system\\.linux\\.network\\.hosts\\.openstack\\.kmn\$' ${infraInitFile}", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
                if (!containsFix) {
                    fixFileContent << '- system.linux.network.hosts.openstack.kmn'
                }
            }
            if (fixFileContent) {
                salt.cmdRun(venvPepper, 'I@salt:master', "echo 'classes:\n${fixFileContent.join('\n')}' > ${fixFile}")
                salt.cmdRun(venvPepper, 'I@salt:master', "sed -i '/^parameters:/i - cluster.${cluster_name}.infra.${fixName}' ${infraInitFile}")
                salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/${cluster_name} && git status && git add ${fixFile}")
            }
        }
    }
}

def wa34528(String cluster_name) {
    // Mysql users have to be defined on each Galera node
    if(salt.getMinions(venvPepper, 'I@galera:master').isEmpty()) {
        common.errorMsg('No Galera master found in cluster. Skipping')
        return
    }
    def mysqlUsersMasterPillar = salt.getPillar(venvPepper, 'I@galera:master', 'mysql:server:database').get("return")[0].values()[0]
    if (mysqlUsersMasterPillar == '' || mysqlUsersMasterPillar == 'null' || mysqlUsersMasterPillar == null) {
        common.errorMsg('Pillar data is broken for Galera master node!')
        input message: 'Do you want to ignore and continue without Galera pillar patch?'
        return
    }
    def fileToPatch = salt.cmdRun(venvPepper, 'I@salt:master', "ls /srv/salt/reclass/classes/cluster/${cluster_name}/openstack/database/init.yml || " +
            "ls /srv/salt/reclass/classes/cluster/${cluster_name}/openstack/database/slave.yml || echo 'File not found'", true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
    if (fileToPatch == 'File not found') {
        common.errorMsg('Cluster model is old and cannot be patched for PROD-34528. Patching is possible for 2019.2.x cluster models only')
        return
    }
    def patchRequired = false
    def mysqlUsersSlavePillar = ''
    def galeraSlaveNodes = salt.getMinions(venvPepper, 'I@galera:slave')
    if (!galeraSlaveNodes.isEmpty()) {
        for (galeraSlave in galeraSlaveNodes) {
            mysqlUsersSlavePillar = salt.getPillar(venvPepper, galeraSlave, 'mysql:server:database').get("return")[0].values()[0]
            if (mysqlUsersSlavePillar == '' || mysqlUsersSlavePillar == 'null' || mysqlUsersSlavePillar == null) {
                common.errorMsg('Mysql users data is not defined for Galera slave nodes. Fixing...')
                patchRequired = true
                break
            }
        }
        if (patchRequired) {
            def fixFileContent = []
            def fixName = 'db_wa34528'
            def fixFile = "/srv/salt/reclass/classes/cluster/${cluster_name}/openstack/database/${fixName}.yml"
            for (dbName in mysqlUsersMasterPillar.keySet()) {
                def classIncluded = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- system\\.galera\\.server\\.database\\.${dbName}\$'" +
                        " /srv/salt/reclass/classes/cluster/${cluster_name}/openstack/database/master.yml", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
                if(classIncluded) {
                    fixFileContent << "- system.galera.server.database.${dbName}"
                }
                def sslClassIncluded = salt.cmdRun(venvPepper, 'I@salt:master', "grep -E '^- system\\.galera\\.server\\.database\\.x509\\.${dbName}\$'" +
                        " /srv/salt/reclass/classes/cluster/${cluster_name}/openstack/database/master.yml", false, null, true).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
                if(sslClassIncluded) {
                    fixFileContent << "- system.galera.server.database.x509.${dbName}"
                }
            }
            if (fixFileContent) {
                salt.cmdRun(venvPepper, 'I@salt:master', "echo 'classes:\n${fixFileContent.join('\n')}' > ${fixFile}")
                salt.cmdRun(venvPepper, 'I@salt:master', "sed -i '/^parameters:/i - cluster.${cluster_name}.openstack.database.${fixName}' ${fileToPatch}")
            }
            salt.fullRefresh(venvPepper, 'I@galera:slave')
            // Verify
            for (galeraSlave in galeraSlaveNodes) {
                mysqlUsersSlavePillar = salt.getPillar(venvPepper, galeraSlave, 'mysql:server:database').get("return")[0].values()[0]
                if (mysqlUsersSlavePillar == '' || mysqlUsersSlavePillar == 'null' || mysqlUsersSlavePillar == null || mysqlUsersSlavePillar.keySet() != mysqlUsersMasterPillar.keySet()) {
                    common.errorMsg("Mysql user data is different on master and slave node ${galeraSlave}.")
                    input message: 'Do you want to ignore and continue?'
                }
            }
            salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/${cluster_name} && git status && git add ${fixFile}")
            common.infoMsg('Galera slaves patching is done')
        } else {
            common.infoMsg('Galera slaves patching is not required')
        }
    }
}

def archiveReclassInventory(filename) {
    def _tmp_file = '/tmp/' + filename + UUID.randomUUID().toString().take(8)
    // jenkins may fail at overheap. Compress data with gzip like WA
    def ret = salt.cmdRun(venvPepper, 'I@salt:master', 'reclass -i  2>/dev/null | gzip -9 -c | base64', true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
    def _tmp = sh(script: "echo '$ret'  > ${_tmp_file}", returnStdout: false)
    sh(script: "cat ${_tmp_file} | base64 -d | gzip -d > $filename", returnStdout: false)
    archiveArtifacts artifacts: filename
    sh(script: "rm -v ${_tmp_file}|| true")
}

def validateReclassModel(ArrayList saltMinions, String suffix) {
    try {
        for (String minion in saltMinions) {
            common.infoMsg("Reclass model validation for minion ${minion}...")
            def reclassInv = salt.cmdRun(venvPepper, 'I@salt:master', "reclass -n ${minion}", true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
            writeFile file: "inventory-${minion}-${suffix}.out", text: reclassInv.toString()
        }
    } catch (Exception e) {
        common.errorMsg('Can not validate current Reclass model. Inspect failed minion manually.')
        error(e.toString())
    }
}

def archiveReclassModelChanges(ArrayList saltMinions, String oldSuffix = 'before', String newSuffix = 'after') {
    for (String minion in saltMinions) {
        def fileName = "reclass-model-${minion}-diff.out"
        sh "diff -u inventory-${minion}-${oldSuffix}.out inventory-${minion}-${newSuffix}.out > ${fileName} || true"
        archiveArtifacts artifacts: "${fileName}"
    }
}

def checkDebsums() {
    // check for salt-formulas consistency
    try {
        try {
            salt.cmdRun(venvPepper, 'I@salt:master', "salt -C 'I@salt:master' pkg.install force_yes=True pkgs=[debsums]")
        }
        catch (Exception ex) {
            common.warningMsg('Unable to install package "debsums" at cfg01. Salt-formulas integrity check skipped')
        }
        salt.cmdRun(venvPepper, 'I@salt:master', '> /root/debdsums_report; for i in $(dpkg-query -W -f=\'${Package}\\n\' | sed "s/ //g" |grep \'salt-formula-\'); do debsums -s ${i} 2>> /root/debdsums_report; done')
        salt.cmdRun(venvPepper, 'I@salt:master', 'if [ -s "/root/debdsums_report" ]; then exit 1 ; fi')
    }
    catch (Exception ex) {
        common.errorMsg(salt.cmdRun(venvPepper, 'I@salt:master', 'cat /root/debdsums_report ', true, null, false).get('return')[0].values()[0].trim())
        common.errorMsg(ex.toString())
        error('You have unexpected changes in formulas. All of them will be overwrited by update. Unable to continue in automatic way')
    }
}

def checkCICDDocker() {
    common.infoMsg('Perform: Checking if Docker containers are up')
    try {
        common.retry(10, 30) {
            salt.cmdRun(venvPepper, 'I@jenkins:client and I@docker:client', "! docker service ls | tail -n +2 | grep -v -E '\\s([0-9])/\\1\\s'")
        }
    }
    catch (Exception ex) {
        error("Docker containers for CI/CD services are having troubles with starting.")
    }
}

if (common.validInputParam('PIPELINE_TIMEOUT')) {
    try {
        pipelineTimeout = env.PIPELINE_TIMEOUT.toInteger()
    } catch (Exception e) {
        common.warningMsg("Provided PIPELINE_TIMEOUT parameter has invalid value: ${env.PIPELINE_TIMEOUT} - should be interger")
    }
}

timeout(time: pipelineTimeout, unit: 'HOURS') {
    node('python') {
        try {
            def inventoryBeforeFilename = "reclass-inventory-before.out"
            def inventoryAfterFilename = "reclass-inventory-after.out"
            workspace = common.getWorkspace()
            targetMcpVersion = null
            if (!common.validInputParam('TARGET_MCP_VERSION') && !common.validInputParam('MCP_VERSION')) {
                error('You must specify MCP version in TARGET_MCP_VERSION|MCP_VERSION variable')
            }
            // bw comp. for 2018.X => 2018.11 release
            if (common.validInputParam('MCP_VERSION')) {
                targetMcpVersion = env.MCP_VERSION
                common.warningMsg("targetMcpVersion has been changed to:${targetMcpVersion}, which was taken from deprecated pipeline viriable:MCP_VERSION")
            } else {
                targetMcpVersion = env.TARGET_MCP_VERSION
            }
            // end bw comp. for 2018.X => 2018.11 release
            def gitTargetMcpVersion = env.getProperty('GIT_REFSPEC')
            if (targetMcpVersion in ['nightly', 'testing']) {
                gitTargetMcpVersion = 'master'
            } else if (targetMcpVersion == 'proposed') {
                gitTargetMcpVersion = 'proposed'
            } else if (!gitTargetMcpVersion) {
                // backward compatibility for 2018.11.0
                gitTargetMcpVersion = "release/${targetMcpVersion}"
            }
            common.warningMsg("gitTargetMcpVersion has been changed to:${gitTargetMcpVersion}")
            def upgradeSaltStack = ''
            def updateClusterModel = ''
            def applyWorkarounds = true
            def updatePipelines = ''
            def updateLocalRepos = ''
            def reclassSystemBranch = ''
            def reclassSystemBranchDefault = gitTargetMcpVersion
            if (gitTargetMcpVersion ==~ /^\d\d\d\d\.\d\d?\.\d+$/) {
                reclassSystemBranchDefault = "tags/${gitTargetMcpVersion}"
            } else if (gitTargetMcpVersion != 'proposed') {
                reclassSystemBranchDefault = "origin/${gitTargetMcpVersion}"
            }
            def driveTrainParamsYaml = env.getProperty('DRIVE_TRAIN_PARAMS')
            if (driveTrainParamsYaml) {
                def driveTrainParams = readYaml text: driveTrainParamsYaml
                saltMastURL = driveTrainParams.get('SALT_MASTER_URL')
                saltMastCreds = driveTrainParams.get('SALT_MASTER_CREDENTIALS')
                upgradeSaltStack = driveTrainParams.get('UPGRADE_SALTSTACK', false).toBoolean()
                updateClusterModel = driveTrainParams.get('UPDATE_CLUSTER_MODEL', false).toBoolean()
                updatePipelines = driveTrainParams.get('UPDATE_PIPELINES', false).toBoolean()
                updateLocalRepos = driveTrainParams.get('UPDATE_LOCAL_REPOS', false).toBoolean()
                reclassSystemBranch = driveTrainParams.get('RECLASS_SYSTEM_BRANCH', reclassSystemBranchDefault)
                batchSize = driveTrainParams.get('BATCH_SIZE', '')
                if (driveTrainParams.get('OS_DIST_UPGRADE', false).toBoolean() == true) {
                    packageUpgradeMode = 'dist-upgrade'
                } else if (driveTrainParams.get('OS_UPGRADE', false).toBoolean() == true) {
                    packageUpgradeMode = 'upgrade'
                }
                applyWorkarounds = driveTrainParams.get('APPLY_MODEL_WORKAROUNDS', true).toBoolean()
            } else {
                // backward compatibility for 2018.11.0
                saltMastURL = env.getProperty('SALT_MASTER_URL')
                saltMastCreds = env.getProperty('SALT_MASTER_CREDENTIALS')
                upgradeSaltStack = env.getProperty('UPGRADE_SALTSTACK').toBoolean()
                updateClusterModel = env.getProperty('UPDATE_CLUSTER_MODEL').toBoolean()
                updatePipelines = env.getProperty('UPDATE_PIPELINES').toBoolean()
                updateLocalRepos = env.getProperty('UPDATE_LOCAL_REPOS').toBoolean()
                reclassSystemBranch = reclassSystemBranchDefault
            }
            python.setupPepperVirtualenv(venvPepper, saltMastURL, saltMastCreds)
            def minions = salt.getMinions(venvPepper, '*')
            def cluster_name = salt.getPillar(venvPepper, 'I@salt:master', "_param:cluster_name").get("return")[0].values()[0]
            if (cluster_name == '' || cluster_name == 'null' || cluster_name == null) {
                error('Pillar data is broken for Salt master node! Please check it manually and re-run pipeline.')
            }
            if (!batchSize) {
                // if no batch size provided get current worker threads and set batch size to 2/3 of it to avoid
                // 'SaltReqTimeoutError: Message timed out' issue on Salt targets for large amount of nodes
                // do not use toDouble/Double as it requires additional approved method
                def workerThreads = getWorkerThreads(venvPepper).toInteger()
                batchSize = (workerThreads * 2 / 3).toString().tokenize('.')[0]
            }
            def computeMinions = salt.getMinions(venvPepper, 'I@nova:compute')
            def allMinions = salt.getMinions(venvPepper, '*')

            stage('Update Reclass and Salt-Formulas') {
                common.infoMsg('Perform: Full salt sync')
                fullRefreshOneByOne(venvPepper, allMinions)

                check_34406(cluster_name)
                check_35705(cluster_name)
                check_35884(cluster_name)
                check_36461(cluster_name)

                common.infoMsg('Perform: Validate reclass medata before processing')
                validateReclassModel(minions, 'before')

                common.infoMsg('Perform: archiveReclassInventory before upgrade')
                archiveReclassInventory(inventoryBeforeFilename)

                try {
                    salt.cmdRun(venvPepper, 'I@salt:master', 'cd /srv/salt/reclass/ && git status && git diff-index --quiet HEAD --')
                }
                catch (Exception ex) {
                    error('You have uncommitted changes in your Reclass cluster model repository. Please commit or reset them and rerun the pipeline.')
                }
                checkDebsums()
                if (updateClusterModel) {
                    common.infoMsg('Perform: UPDATE_CLUSTER_MODEL')
                    def dateTime = common.getDatetime()
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/system && git fetch")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'mcp_version: .*' * | xargs --no-run-if-empty sed -i 's|mcp_version: .*|mcp_version: \"$targetMcpVersion\"|g'")
                    // Do the same, for deprecated variable-duplicate
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'apt_mk_version: .*' * | xargs --no-run-if-empty sed -i 's|apt_mk_version: .*|apt_mk_version: \"$targetMcpVersion\"|g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'jenkins_pipelines_branch: .*' * | xargs --no-run-if-empty sed -i 's|jenkins_pipelines_branch: .*|jenkins_pipelines_branch: \"$gitTargetMcpVersion\"|g'")
                    // Set new k8s param
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'kubernetes_containerd_enabled: .*' * | xargs --no-run-if-empty sed -i 's|kubernetes_containerd_enabled: .*|kubernetes_containerd_enabled: True|g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'system.linux.system.repo.mcp.salt' * | xargs --no-run-if-empty sed -i 's/system.linux.system.repo.mcp.salt/system.linux.system.repo.mcp.apt_mirantis.salt-formulas/g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'system.linux.system.repo.mcp.contrail' * | xargs --no-run-if-empty sed -i 's/system.linux.system.repo.mcp.contrail/system.linux.system.repo.mcp.apt_mirantis.contrail/g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'system.linux.system.repo.mcp.updates\$' * | xargs --no-run-if-empty sed -i 's/system.linux.system.repo.mcp.updates\$/system.linux.system.repo.mcp.apt_mirantis.update/g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly -l 'system.linux.system.repo.mcp.extra' * | xargs --no-run-if-empty sed -i 's/system.linux.system.repo.mcp.extra/system.linux.system.repo.mcp.apt_mirantis.extra/g'")

                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name/infra && sed -i '/linux_system_repo_mcp_maas_url/d' maas.yml")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name/infra && sed -i '/maas_region_main_archive/d' maas.yml")

                    // Switch Jenkins/Gerrit to use LDAP SSL/TLS
                    def gerritldapURI = salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly 'gerrit_ldap_server: .*' * | grep -Po 'gerrit_ldap_server: \\K.*' | tr -d '\"'", true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
                    if (gerritldapURI.startsWith('ldap://')) {
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -r --exclude-dir=aptly -l 'gerrit_ldap_server: .*' * | xargs --no-run-if-empty sed -i 's|ldap://|ldaps://|g'")
                    } else if (! gerritldapURI.startsWith('ldaps://')) {
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -r --exclude-dir=aptly -l 'gerrit_ldap_server: .*' * | xargs --no-run-if-empty sed -i 's|gerrit_ldap_server: .*|gerrit_ldap_server: \"ldaps://${gerritldapURI}\"|g'")
                    }
                    def jenkinsldapURI = salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r --exclude-dir=aptly 'jenkins_security_ldap_server: .*' * | grep -Po 'jenkins_security_ldap_server: \\K.*' | tr -d '\"'", true, null, false).get('return')[0].values()[0].replaceAll('Salt command execution success', '').trim()
                    if (jenkinsldapURI.startsWith('ldap://')) {
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -r --exclude-dir=aptly -l 'jenkins_security_ldap_server: .*' * | xargs --no-run-if-empty sed -i 's|ldap://|ldaps://|g'")
                    } else if (! jenkinsldapURI.startsWith('ldaps://')) {
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -r --exclude-dir=aptly -l 'jenkins_security_ldap_server: .*' * | xargs --no-run-if-empty sed -i 's|jenkins_security_ldap_server: .*|jenkins_security_ldap_server: \"ldaps://${jenkinsldapURI}\"|g'")
                    }

                    if (applyWorkarounds) {
                        wa32284(cluster_name)
                        wa34245(cluster_name)
                    }

                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/system && git checkout ${reclassSystemBranch}")
                    // Add kubernetes-extra repo
                    if (salt.testTarget(venvPepper, "I@kubernetes:master")) {
                        // docker-engine conflicts with the recent containerd versions, so it's removed during upgrade. Thus update source engine
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -r -l 'engine: docker_hybrid' kubernetes | xargs --no-run-if-empty sed -i 's/engine: docker_hybrid/engine: archive/g'")
                        common.infoMsg("Add kubernetes-extra repo")
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -q system.linux.system.repo.mcp.apt_mirantis.update.kubernetes_extra kubernetes/common.yml || sed -i '/classes:/ a - system.linux.system.repo.mcp.apt_mirantis.update.kubernetes_extra' kubernetes/common.yml")
                        salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                            "grep -q system.linux.system.repo.mcp.apt_mirantis.kubernetes_extra kubernetes/common.yml || sed -i '/classes:/ a - system.linux.system.repo.mcp.apt_mirantis.kubernetes_extra' kubernetes/common.yml")
                    }
                    // Add all update repositories
                    def repoIncludeBase = '- system.linux.system.repo.mcp.apt_mirantis.'
                    def updateRepoList = ['cassandra', 'ceph', 'contrail', 'docker', 'elastic', 'extra', 'openstack', 'maas', 'percona', 'salt-formulas', 'saltstack', 'ubuntu']
                    updateRepoList.each { repo ->
                        def repoNameUpdateInclude = "${repoIncludeBase}update.${repo}"
                        def filesWithInclude = salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && grep -Plr '\\${repoIncludeBase}${repo}\$' . || true", false).get('return')[0].values()[0].trim().tokenize('\n')
                        filesWithInclude.each { file ->
                            def updateRepoIncludeExist = salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && grep -P '\\${repoNameUpdateInclude}\$' ${file} || echo not_found", false, null, true).get('return')[0].values()[0].trim()
                            if (updateRepoIncludeExist == 'not_found') {
                                // Include needs to be added
                                salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                                    "sed -i 's/\\( *\\)${repoIncludeBase}${repo}\$/&\\n\\1${repoNameUpdateInclude}/g' ${file}")
                                common.infoMsg("Update repo for ${repo} is added to ${file}")
                            }
                        }
                    }
                    if (applyWorkarounds) {
                        wa32182(cluster_name)
                        wa33771(cluster_name)
                        wa33867(cluster_name)
                        wa33930_33931(cluster_name)
                        wa34528(cluster_name)
                    }
                    // Add new defaults
                    common.infoMsg("Add new defaults")
                    salt.cmdRun(venvPepper, 'I@salt:master', "grep '^    mcp_version: ' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml || " +
                        "sed -i 's|^  _param:|  _param:\\n    mcp_version: \"$targetMcpVersion\"|' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml")
                    salt.cmdRun(venvPepper, 'I@salt:master', "grep '^- system.defaults\$' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml || " +
                        "sed -i 's|^classes:|classes:\\n- system.defaults|' /srv/salt/reclass/classes/cluster/$cluster_name/infra/init.yml")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r -l 'docker_image_jenkins: .*' cicd | xargs --no-run-if-empty sed -i 's|\\s*docker_image_jenkins: .*||g'")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && " +
                        "grep -r -l 'docker_image_jenkins_slave: .*' cicd | xargs --no-run-if-empty sed -i 's|\\s*docker_image_jenkins_slave: .*||g'")
                    common.infoMsg("The following changes were made to the cluster model and will be commited. " +
                        "Please consider if you want to push them to the remote repository or not. You have to do this manually when the run is finished.")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && git diff")
                    salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/$cluster_name && git status && " +
                        "git add -u && git commit --allow-empty -m 'Cluster model update to the release $targetMcpVersion on $dateTime'")
                }
                try {
                    common.infoMsg('Perform: UPDATE Salt Formulas')
                    fullRefreshOneByOne(venvPepper, allMinions)
                    salt.enforceState(venvPepper, 'I@salt:master', 'linux.system.repo', true, true, null, false, 60, 2)
                    def saltEnv = salt.getPillar(venvPepper, 'I@salt:master', "_param:salt_master_base_environment").get("return")[0].values()[0]
                    salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'state.sls_id', ["salt_master_${saltEnv}_pkg_formulas", 'salt.master.env'])
                    fullRefreshOneByOne(venvPepper, allMinions)
                } catch (Exception updateErr) {
                    common.warningMsg(updateErr)
                    common.warningMsg('Failed to update Salt Formulas repos/packages. Check current available documentation on https://docs.mirantis.com/mcp/latest/, how to update packages.')
                    input message: 'Continue anyway?'
                }
                if (applyWorkarounds) {
                    wa29352(cluster_name)
                    wa29155(computeMinions, cluster_name)
                }

                try {
                    common.infoMsg('Perform: UPDATE Reclass package')
                    salt.runSaltProcessStep(venvPepper, 'I@salt:master', 'pkg.install', ["reclass"])
                } catch (Exception updateErr) {
                    common.warningMsg(updateErr)
                    common.warningMsg('Failed to update Reclass package. Check current available documentation on https://docs.mirantis.com/mcp/latest/, how to update packages.')
                    input message: 'Continue anyway?'
                }

                salt.fullRefresh(venvPepper, 'I@salt:master')
                salt.enforceState(venvPepper, 'I@salt:master', 'reclass.storage', true, true, null, false, 60, 2)
                salt.cmdRun(venvPepper, 'I@salt:master', "cd /srv/salt/reclass/classes/cluster/${cluster_name} && git status && " +
                        "git add -u && git commit --allow-empty -m 'Reclass nodes update to the release ${targetMcpVersion} on ${common.getDatetime()}'")
                try {
                    salt.enforceState(venvPepper, 'I@salt:master', 'reclass', true, true, null, false, 60, 2)
                }
                catch (Exception ex) {
                    common.errorMsg(ex.toString())
                    error('Reclass fails rendering. Pay attention to your cluster model.')
                }

                fullRefreshOneByOne(venvPepper, allMinions)
                try {
                    salt.cmdRun(venvPepper, 'I@salt:master', "reclass-salt --top")
                }
                catch (Exception ex) {

                    error('Reclass fails rendering. Pay attention to your cluster model.' +
                        'ErrorMessage:' + ex.toString())
                }

                common.infoMsg('Perform: archiveReclassInventory AFTER upgrade')
                archiveReclassInventory(inventoryAfterFilename)

                sh "diff -u $inventoryBeforeFilename $inventoryAfterFilename > reclass-inventory-diff.out || true"
                archiveArtifacts artifacts: "reclass-inventory-diff.out"

                validateReclassModel(minions, 'after')
                archiveReclassModelChanges(minions)
            }

            if (updateLocalRepos) {
                stage("Update local repos") {
                    common.infoMsg("Updating local repositories")

                    def engine = salt.getPillar(venvPepper, 'I@aptly:publisher', "aptly:publisher:source:engine")
                    runningOnDocker = engine.get("return")[0].containsValue("docker")

                    if (runningOnDocker) {
                        common.infoMsg("Aptly is running as Docker container")
                    } else {
                        common.infoMsg("Aptly isn't running as Docker container. Going to use aptly user for executing aptly commands")
                    }

                    if (runningOnDocker) {
                        salt.cmdRun(venvPepper, 'I@aptly:publisher', "aptly mirror list --raw | grep -E '*' | xargs --no-run-if-empty -n 1 aptly mirror drop -force", true, null, true)
                    } else {
                        salt.cmdRun(venvPepper, 'I@aptly:publisher', "aptly mirror list --raw | grep -E '*' | xargs --no-run-if-empty -n 1 aptly mirror drop -force", true, null, true, ['runas=aptly'])
                    }

                    salt.enforceState(venvPepper, 'I@aptly:publisher', 'aptly', true)

                    if (runningOnDocker) {
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:publisher', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv"], null, true)
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:publisher', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-frv -u http://10.99.0.1:8080"], null, true)
                    } else {
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:publisher', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv", 'runas=aptly'], null, true)
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:publisher', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-afrv", 'runas=aptly'], null, true)
                    }

                    salt.enforceState(venvPepper, 'I@aptly:publisher', 'docker.client.registry', true)

                    salt.enforceState(venvPepper, 'I@aptly:publisher', 'debmirror', true)

                    salt.enforceState(venvPepper, 'I@aptly:publisher', 'git.server', true)

                    salt.enforceState(venvPepper, 'I@aptly:publisher', 'linux.system.file', true)
                }
            }

            stage('Update Drivetrain: Part 1') {
                salt.enforceState(venvPepper, 'I@linux:system', 'linux.system.repo', true, true, batchSize, false, 60, 2)
                salt.enforceState(venvPepper, '*', 'linux.system.package', true, true, batchSize, false, 60, 2)

                if (upgradeSaltStack) {
                    updateSaltStack('I@salt:master', '["salt-master", "salt-common", "salt-api", "salt-minion"]')
                    updateSaltStack('I@salt:minion and not I@salt:master', '["salt-minion"]')
                }

                if (updatePipelines) {
                    common.infoMsg('Perform: UPDATE git repos')
                    triggerMirrorJob('git-mirror-downstream-mk-pipelines', reclassSystemBranch)
                    triggerMirrorJob('git-mirror-downstream-pipeline-library', reclassSystemBranch)
                }

                // update minions certs
                // call for `salt.minion.ca` state on related nodes to make sure
                // mine was updated with required data after salt-minion/salt-master restart salt:minion:ca
                salt.enforceState(venvPepper, 'I@salt:minion:ca', 'salt.minion.ca', true, true, batchSize, false, 60, 2)
                salt.enforceState(venvPepper, 'I@salt:minion', 'salt.minion.cert', true, true, batchSize, false, 60, 2)

                // run `salt.minion` to refresh all minion configs (for example _keystone.conf)
                salt.enforceState(venvPepper, 'I@salt:minion', 'salt.minion', true, true, batchSize, false, 60, 2)
                // Retry needed only for rare race-condition in user appearance
                common.infoMsg('Perform: updating users and keys')
                salt.enforceState(venvPepper, 'I@linux:system', 'linux.system.user', true, true, batchSize, false, 60, 2)
                common.infoMsg('Perform: updating openssh')
                salt.enforceState(venvPepper, 'I@linux:system', 'openssh', true, true, batchSize, false, 60, 2)

                // apply salt API TLS if needed
                def nginxAtMaster = salt.getPillar(venvPepper, 'I@salt:master', 'nginx:server:enabled').get('return')[0].values()[0]
                if (nginxAtMaster.toString().toLowerCase() == 'true') {
                    salt.enforceState(venvPepper, 'I@salt:master', 'nginx', true, true, null, false, 60, 2)
                }
            }
        }
        catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
    stage('Upgrade OS') {
        if (packageUpgradeMode) {
            def cidNodes = []
            node('python') {
                python.setupPepperVirtualenv(venvPepper, saltMastURL, saltMastCreds)
                cidNodes = salt.getMinions(venvPepper, 'I@_param:drivetrain_role:cicd')
            }
            def debian = new com.mirantis.mk.Debian()
            def statusFile = '/tmp/rebooted_during_upgrade'
            for(cidNode in cidNodes) {
                node('python') {
                    python.setupPepperVirtualenv(venvPepper, saltMastURL, saltMastCreds)
                    // cmd.run async to prevent connection close in case of slave shutdown, give 5 seconds to handle request response
                    salt.cmdRun(venvPepper, "I@salt:master", "salt -C '${cidNode}' cmd.run 'sleep 5; touch ${statusFile}; salt-call service.stop docker' --async")
                }
                sleep(30)
                node('python') {
                    python.setupPepperVirtualenv(venvPepper, saltMastURL, saltMastCreds)
                    debian.osUpgradeNode(venvPepper, cidNode, packageUpgradeMode, false, 60)
                    salt.checkTargetMinionsReady(['saltId': venvPepper, 'target': cidNode, wait: 60, timeout: 10])
                    if (salt.runSaltProcessStep(venvPepper, cidNode, 'file.file_exists', [statusFile], null, true, 5)['return'][0].values()[0].toBoolean()) {
                        salt.cmdRun(venvPepper, "I@salt:master", "salt -C '${cidNode}' cmd.run 'rm ${statusFile} && salt-call service.start docker'") // in case if node was not rebooted
                        sleep(10)
                    }
                    checkCICDDocker()
                }
            }
        } else {
            common.infoMsg('Upgrade OS skipped...')
        }
    }

    node('python') {
        stage('Update Drivetrain: Part 2') {
            python.setupPepperVirtualenv(venvPepper, saltMastURL, saltMastCreds)
            // Gerrit 2019.2.0 (2.13.6) version has wrong file name for download-commands plugin and was not loaded, let's remove if still there before upgrade
            def gerritGlusterPath = salt.getPillar(venvPepper, 'I@gerrit:client', 'glusterfs:client:volumes:gerrit:path').get('return')[0].values()[0]
            def wrongPluginJarName = "${gerritGlusterPath}/plugins/project-download-commands.jar"
            salt.cmdRun(venvPepper, 'I@gerrit:client', "test -f ${wrongPluginJarName} && rm ${wrongPluginJarName} || true")

            salt.enforceStateWithTest(venvPepper, 'I@jenkins:client:security and not I@salt:master', 'jenkins.client.security', "", true, true, null, true, 60, 2)
            salt.enforceStateWithTest(venvPepper, 'I@jenkins:client and I@docker:client:images and not I@salt:master', 'docker.client.images', "", true, true, null, true, 60, 2)
            salt.cmdRun(venvPepper, "I@salt:master", "salt -C 'I@jenkins:client and I@docker:client and not I@salt:master' state.sls docker.client --async")
        }
    }
    // docker.client state may trigger change of jenkins master or jenkins slave services,
    // so we need wait for slave to reconnect and continue pipeline
    sleep(180)
    node('python') {
        stage('Update Drivetrain: Part 3') {
            python.setupPepperVirtualenv(venvPepper, saltMastURL, saltMastCreds)
            checkCICDDocker()

            // update Nginx proxy settings for Jenkins/Gerrit if needed
            if (salt.testTarget(venvPepper, 'I@nginx:server:site:nginx_proxy_jenkins and I@nginx:server:site:nginx_proxy_gerrit')) {
                salt.enforceState(venvPepper, 'I@nginx:server:site:nginx_proxy_jenkins and I@nginx:server:site:nginx_proxy_gerrit', 'nginx.server', true, true, null, false, 60, 2)
            }
            // Apply changes for HaProxy on CI/CD nodes
            salt.enforceState(venvPepper, 'I@keepalived:cluster:instance:cicd_control_vip and I@haproxy:proxy', 'haproxy.proxy', true)
            salt.upgradePackageAndRestartSaltMinion(venvPepper, 'I@jenkins:client and not I@salt:master', 'python-jenkins')
            salt.cmdRun(venvPepper, "I@salt:master", "salt -C 'I@jenkins:client and not I@salt:master' state.sls jenkins.client --async")

            common.warningMsg("Jenkins update started in background in order to handle plugin post-install issues.")
            common.warningMsg("Please wait until it finished. Jenkins could be restarted during this procedure.")
            common.warningMsg("You can monitor job progress by running 'salt-run jobs.active' on master node")
            common.warningMsg("For ensuring that upgrade is done and there are no errors you can run the following command on salt master node")
            common.warningMsg("salt-run jobs.lookup_jid %salt_job_id%")
            common.warningMsg("Salt job ID could be found in the log above.")
        }
    }
}
