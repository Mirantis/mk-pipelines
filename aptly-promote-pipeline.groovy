def common = new com.mirantis.mk.Common()
def aptly = new com.mirantis.mk.Aptly()
def jenkinsUtils = new com.mirantis.mk.JenkinsUtils()

def packages
try {
    packages = PACKAGES
} catch (MissingPropertyException e) {
    packages = ""
}

def components
try {
    components = COMPONENTS
} catch (MissingPropertyException e) {
    components = ""
}

def storages
try {
    storages = STORAGES.tokenize(',')
} catch (MissingPropertyException e) {
    storages = ['local']
}

def insufficientPermissions = false

timeout(time: 12, unit: 'HOURS') {
    node("docker&&hardware") {
        try {

            if (!jenkinsUtils.currentUserInGroup(["release-engineering", "aptly-promote-users"])) {
                insufficientPermissions = true
                throw new Exception("Only release-engineering or aptly-promote-users can perform promote to " + TARGET)
            }
            stage("promote") {
                // promote is restricted to users in aptly-promote-users LDAP group
                lock("aptly-api") {
                    for (storage in storages) {
                        if (storage == "local") {
                            storage = ""
                        }
                        retry(2) {
                            aptly.promotePublish(APTLY_URL, SOURCE, TARGET, RECREATE, components, packages, DIFF_ONLY, '-d --timeout 600', DUMP_PUBLISH.toBoolean(), storage)
                        }
                    }
                }
            }
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
        } finally {
            if (!insufficientPermissions) {
                common.sendNotification(currentBuild.result, "", ["slack"])
                def _extra_descr = "${SOURCE}=>${TARGET}:\n${COMPONENTS} ${packages}"
                currentBuild.description = currentBuild.description ? _extra_descr + " " + currentBuild.description : _extra_descr
            }
        }
    }
}

