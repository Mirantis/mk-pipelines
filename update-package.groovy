/**
 * Update packages pipeline script
 *
 * Expected parameters:
 * SALT_MASTER_CREDENTIALS     Credentials to the Salt API (str)
 * SALT_MASTER_URL             URL of Salt-API (str)
 *
 * UPDATE_SERVERS              Salt target for updated servers (str)
 * UPDATE_COMMIT               Confirm update should be performed (boot)
 * UPDATE_PACKAGES             List of packages to update
 *
**/



def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
timestamps {
    node() {
      try {

        stage("Connect to Salt master") {
          saltMaster = salt.connection(SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
        }

        stage("Get package versions") {
          salt.runSaltProcessStep(saltMaster, UPDATE_SERVERS, 'pkg.list_upgrades', [], null, true)
        }

        if (UPDATE_COMMIT.toBoolean() == true) {
          stage("Update packages") {
            if (UPDATE_PACKAGES == "") {
              salt.runSaltProcessStep(saltMaster, UPDATE_SERVERS, 'pkg.install', [], null, true)
            } else {
              salt.runSaltProcessStep(saltMaster, UPDATE_SERVERS, 'pkg.install', UPDATE_PACKAGES.split(' '), null, true)
            }
          }
        }

      } catch (Throwable e) {
         // If there was an error or exception thrown, the build failed
         currentBuild.result = "FAILURE"
         throw e
      } finally {
         // common.sendNotification(currentBuild.result,"",["slack"])
      }
    }
}
