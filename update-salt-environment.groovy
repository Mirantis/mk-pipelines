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
def venvPepper = "venvPepper"
timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
            python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)

            stage("Update formulas"){
                if(UPDATE_FORMULAS.toBoolean()){
                    common.infoMsg("Updating salt formulas")
                    salt.cmdRun(
                        venvPepper,
                        "I@salt:master",
                        'apt-get update && apt-get install -y salt-formula-*'
                    )
                    common.infoMsg("Running salt sync-all")
                    salt.runSaltProcessStep(venvPepper, 'jma*', 'saltutil.sync_all', [], null, true)
                }
            }
            stage("Update Reclass") {
                common.infoMsg("Updating reclass model")
                salt.cmdRun(
                    venvPepper,
                    "I@salt:master",
                    'cd /srv/salt/reclass && git pull -r && git submodule update',
                    false
                )

                salt.enforceState(
                    venvPepper,
                    "I@salt:master",
                    'reclass',
                    true
                )
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}