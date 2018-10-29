#!groovy

/**
 * (Re-)Create git branches
 *
 * @param GIT_REPO_LIST   List of repositories to handle
 *     Multiline text: '<name> <url> <src_obj>' (full format)
 *                 or: '<url>' (assuming src_obj=='SUBS_SOURCE_REF')
 * @param GIT_CREDENTIALS Credentials ID to use for the ALL given repositories
 * @param BRANCH          New branch name
 * @param SOURCE_REVISION Source object (commit/tag/branch) to apply to all repos
 *     having empty src_obj or src_obj=='SUBS_SOURCE_REF'
 *
 * @see <a href="https://mirantis.jira.com/browse/PROD-17759">PROD-17759</a>
 */

// Get job environment to use as a map to get values with defaults
Map jobEnv = env.getEnvironment().findAll { k, v -> v }

// Prepare job parameters
ArrayList gitRepoList   = jobEnv.get('GIT_REPO_LIST', '').readLines()
String gitBranchNew     = jobEnv.get('BRANCH')
String srcObj           = jobEnv.get('SOURCE_REVISION', 'master')
String gitCredentialsId = jobEnv.get('GIT_CREDENTIALS')

// Check if new branch name is given
if (! gitBranchNew) {
    error ('No new branch name is given')
}

/**
 * Returns local path for the given URL constructed from hostname and repository
 *
 * @param  repoUrl git repository URL
 * @return string representing local relative patch
 */
String getRepoLocalPath(String repoUrl) {
    // Regex to split git repository URLs
    String re = '^(?:(?<proto>[a-z]+)://)?(?:(?<creds>[^@]+)@)?(?<host>[^:/]+)(?::(?<port>[0-9]+)/|[:/])(?<repo>.+)$'

    java.util.regex.Matcher urlMatcher = repoUrl =~ re
    if (urlMatcher.matches()) {
        return new File(
            urlMatcher.group('host'),
            urlMatcher.group('repo').replaceAll(/\.git$/,'')
        ).toString()
    } else {
        return ''
    }
}

// Variables to use as repo parameters
String gitRepoName
String gitRepoUrl
String gitSrcObj

// Store current commit SHA
String gitCommit

node() {
    for (gitRepo in gitRepoList) {
        (gitRepoName, gitRepoUrl, gitSrcObj) = gitRepo.trim().tokenize(' ')

        if (gitRepoName.startsWith('#')){
            echo ("Skipping repo '${gitRepo}'")
            continue
        }

        if (! gitRepoUrl) {
        // The only token is the git repo url
            gitRepoUrl = gitRepoName
            gitRepoName = getRepoLocalPath(gitRepoUrl)
            gitSrcObj = srcObj
        } else if (! gitSrcObj) {
        // Two tokens - can't decide is gitRepoName or gitSrcObj given
            error ("Wrong repository string format: '${gitRepo}'")
        }

        if (gitSrcObj.contains('SUBS_SOURCE_REF')) {
            echo ("Replacing 'SUBS_SOURCE_REF' => ${SOURCE_REVISION}")
            gitSrcObj = gitSrcObj.replace('SUBS_SOURCE_REF', srcObj)
        }

        checkout([
            $class: 'GitSCM',
            userRemoteConfigs: [
                [url: gitRepoUrl, credentialsId: gitCredentialsId],
            ],
            extensions: [
                [$class: 'PruneStaleBranch'],
                [$class: 'RelativeTargetDirectory', relativeTargetDir: gitRepoName],
                [$class: 'SubmoduleOption', disableSubmodules: true],
                [$class: 'UserIdentity', name: 'MCP CI', email: 'ci+infra@mirantis.com'],
            ],
        ])

        // Proceed branch creation
        dir(gitRepoName) {
            sshagent (credentials: [gitCredentialsId]) {
                // FIXME: Ensure git has configured user and email
                // See: https://issues.jenkins-ci.org/browse/JENKINS-46052
                sh 'git config user.name "MCP CI"'
                sh 'git config user.email "ci+infra@mirantis.com"'

                // Update list of branches
                sh 'git checkout master'

                int is_branch = sh(script: "git ls-remote --exit-code --heads origin ${gitBranchNew}", returnStatus: true)
                int is_tag    = sh(script: "git ls-remote --exit-code --tags  origin ${gitBranchNew}", returnStatus: true)

                // Ensure there is no branch or tag with gitBranchNew name
                if (is_branch == 0) {
                    sh """\
                        git checkout 'origin/${gitBranchNew}' -t || :
                        git checkout master
                        git branch -d '${gitBranchNew}'
                        git push origin ':refs/heads/${gitBranchNew}'
                    """.stripIndent()
                }
                if (is_tag == 0) {
                    sh """\
                        git tag -d '${gitBranchNew}'
                        git push origin ':refs/tags/${gitBranchNew}'
                    """
                }

                // Create new branch
                sh "git branch '${gitBranchNew}' '${gitSrcObj}'" // Create new local branch
                sh "git push --force origin '${gitBranchNew}'"   // ... push new branch
            }
        }
    }
}
