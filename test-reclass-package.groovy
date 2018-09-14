/**
 * Check new Reclass version against current model.
 *
 * Expected parameters:
 *   SALT_MASTER_CREDENTIALS            Credentials to the Salt API.
 *   SALT_MASTER_URL                    Full Salt API address [http://10.10.10.1:8000].
 *   DISTRIB_REVISION                   Mirror version to use
 *   EXTRA_REPO_PREDEFINED              Use mcp extra repo defined on host
 *   EXTRA_REPO                         Extra repo to use in format (for example, deb [arch=amd64] http://apt.mirantis.com/xenial/ nightly extra)
 *   EXTRA_REPO_GPG_KEY_URL             GPG key URL for extra repo
 *   TARGET_NODES                       Target specification, e.g. 'I@openssh:server'
**/

def common = new com.mirantis.mk.Common()
def salt = new com.mirantis.mk.Salt()
def saltModel = new com.mirantis.mk.SaltModelTesting()
def python = new com.mirantis.mk.Python()

def env = "env"
def extraRepo = env.EXTRA_REPO
def extraRepoKey = env.EXTRA_REPO_GPG_KEY_URL
def targetNodes = env.TARGET_NODES
def distribRevision = env.DISTRIB_REVISION
def usePredefinedExtra = env.EXTRA_REPO_PREDEFINED
node('cfg') {

    stage('Setup virtualenv for Pepper') {
      python.setupPepperVirtualenv(env, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
    }

    def minions = salt.getMinionsSorted(env, targetNodes)
    if (usePredefinedExtra) {
      def mcp_extra = salt.getPillar(env, 'I@salt:master', "linux:system:repo:mcp_extra").get("return")[0].values()[0]
      extraRepoKey = mcp_extra['key_url']
      extraRepo = mcp_extra['source']
    }
    def config = [
      'distribRevision': distribRevision,
      'targetNodes': minions,
      'extraRepo': extraRepo,
      'extraRepoKey': extraRepoKey,
      'venv': env
    ]
    saltModel.compareReclassVersions(config)
}
