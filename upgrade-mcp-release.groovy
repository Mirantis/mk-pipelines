/**
 *
 * Update Salt environment pipeline
 *
 * Expected parameters:
 *   SALT_MASTER_URL            Salt API server location
 *   SALT_MASTER_CREDENTIALS    Credentials to the Salt API
 *   MCP_VERSION                Version of MCP to upgrade to
 *   UPDATE_LOCAL_REPOS         Update local repositories
 */

// Load shared libs
salt = new com.mirantis.mk.Salt()
common = new com.mirantis.mk.Common()
python = new com.mirantis.mk.Python()
venvPepper = "venvPepper"

timeout(time: 12, unit: 'HOURS') {
    node("python") {
        try {
            python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

            stage("Update Reclass"){
                common.infoMsg("Updating reclass model")
                salt.cmdRun(venvPepper, "I@salt:master", 'cd /srv/salt/reclass && git pull -r && git submodule update', false)
                salt.cmdRun(venvPepper, 'I@salt:master', 'reclass-salt --top')
                salt.enforceState(venvPepper, "I@salt:master", 'reclass', true)
            }

            if(UPDATE_LOCAL_REPOS.toBoolean()){
                stage("Update local repos"){
                    common.infoMsg("Updating local repositories")

                    def engine = salt.getPillar(venvPepper, 'I@aptly:server', "aptly:server:source:engine")
                    runningOnDocker = engine.get("return")[0].containsValue("docker")

                    if (runningOnDocker) {
                        common.infoMsg("Aptly is running as Docker container")
                    }
                    else {
                        common.infoMsg("Aptly isn't running as Docker container. Going to use aptly user for executing aptly commands")
                    }

                    if(runningOnDocker){
                        salt.cmdRun(venvPepper, 'I@aptly:server', "aptly mirror list --raw | grep -E '*' | xargs -n 1 aptly mirror drop -force", true, null, true)
                    }
                    else{
                       salt.cmdRun(venvPepper, 'I@aptly:server', "aptly mirror list --raw | grep -E '*' | xargs -n 1 aptly mirror drop -force", true, null, true, ['runas=aptly'])
                    }

                    salt.enforceState(venvPepper, 'I@aptly:server', 'aptly', true)

                    if(runningOnDocker){
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv"], null, true)
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-frv -u http://10.99.0.1:8080"], null, true)
                    }
                    else{
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_mirror_update.sh', "args=-sv", 'runas=aptly'], null, true)
                        salt.runSaltProcessStep(venvPepper, 'I@aptly:server', 'cmd.script', ['salt://aptly/files/aptly_publish_update.sh', "args=-afrv", 'runas=aptly'], null, true)
                    }

                    salt.enforceState(venvPepper, 'I@aptly:server', 'docker.client.registry', true)

                    salt.enforceState(venvPepper, 'I@aptly:server', 'debmirror', true)

                    salt.enforceState(venvPepper, 'I@aptly:server', 'git.server', true)

                    salt.enforceState(venvPepper, 'I@aptly:server', 'linux.system.file', true)
                }
            }

            stage("Update APT repos"){
                common.infoMsg("Updating APT repositories")
                salt.enforceState(venvPepper, "I@linux:system", 'linux.system.repo', true)
            }

            stage("Update formulas"){
                common.infoMsg("Updating salt formulas")
                salt.cmdRun(venvPepper, "I@salt:master", 'apt-get clean && apt-get update && apt-get install -y salt-formula-*')

                common.infoMsg("Running salt sync-all")
                salt.runSaltProcessStep(venvPepper, '*', 'saltutil.sync_all', [], null, true)
            }
        }
        catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}
