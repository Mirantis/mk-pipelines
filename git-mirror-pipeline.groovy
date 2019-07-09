def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()

stage('Mirror') {
    timeout(time: 12, unit: 'HOURS') {
        node() {
            try {
                def sourceCreds = env.SOURCE_CREDENTIALS
                if (sourceCreds && common.getCredentialsById(sourceCreds, 'password')) {
                    withCredentials([
                            [$class          : 'UsernamePasswordMultiBinding',
                             credentialsId   : sourceCreds,
                             passwordVariable: 'GIT_PASS',
                             usernameVariable: 'GIT_USER']
                    ]) {
                        sh """
                            set +x
                            git config --global credential.${SOURCE_URL}.username \${GIT_USER}
                            echo "echo \${GIT_PASS}" > askpass.sh && chmod +x askpass.sh
                        """
                        env.GIT_ASKPASS = "${env.WORKSPACE}/askpass.sh"
                    }
                }
                if (BRANCHES == '*' || BRANCHES.contains('*')) {
                    branches = git.getBranchesForGitRepo(SOURCE_URL, BRANCHES)
                } else {
                    branches = BRANCHES.tokenize(',')
                }
                common.infoMsg('branches to fetch:' + branches.toString())
                def pollBranches = []
                for (i = 0; i < branches.size(); i++) {
                    pollBranches.add([name: branches[i]])
                }
                dir('source') {
                    checkout changelog: true, poll: true,
                        scm: [$class    : 'GitSCM', branches: pollBranches, doGenerateSubmoduleConfigurations: false,
                              extensions: [[$class: 'CleanCheckout']], submoduleCfg: [],
                              userRemoteConfigs: [[credentialsId: sourceCreds, url: SOURCE_URL]]]
                    git.mirrorGit(SOURCE_URL, TARGET_URL, CREDENTIALS_ID, branches, true)
                }
            } catch (Throwable e) {
                // If there was an error or exception thrown, the build failed
                currentBuild.result = 'FAILURE'
                currentBuild.description = currentBuild.description ? e.message + '' + currentBuild.description : e.message
                throw e
            } finally {
                sh "git config --global --unset credential.${SOURCE_URL}.username || true"
                deleteDir()
            }
        }
    }
}
