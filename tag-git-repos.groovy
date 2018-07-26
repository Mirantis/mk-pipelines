/**
 *
 * Tag Git repositories
 *
 * Expected parameters:
 *   GIT_REPO_LIST
 *   GIT_CREDENTIALS
 *   TAG
 *   SOURCE_TAG initial commit\tag to be tagged with TAG
 *
 */

common = new com.mirantis.mk.Common()
git = new com.mirantis.mk.Git()

def gitRepoAddTag(repoURL, repoName, tag, credentials, ref = "HEAD"){
    git.checkoutGitRepository(repoName, repoURL, "master", credentials)
    dir(repoName) {
        sh "git tag -f -a ${tag} ${ref} -m \"Release of mcp version ${tag}\""
        sshagent([credentials]) {
            sh "git push -f origin ${tag}:refs/tags/${tag}"
        }
    }
}

timeout(time: 12, unit: 'HOURS') {
    node() {
        try {
            def repos = GIT_REPO_LIST.tokenize('\n')
            def repoUrl, repoName, repoCommit, repoArray
            for (repo in repos){
                if(repo.startsWith('#'))
                  common.warningMsg("Skipping:" + repo.toString())
                  continue
                if(repo.trim().indexOf(' ') == -1){
                    throw new IllegalArgumentException("Wrong format of repository and commit input")
                }
                repoArray = repo.trim().tokenize(' ')
                repoName = repoArray[0]
                repoUrl = repoArray[1]
                repoCommit = repoArray[2]
                if (repoCommit.contains('SUBS_SOURCE_REF')) {
                    common.warningMsg("Replacing SUBS_SOURCE_REF => ${SOURCE_TAG}")
                    repoCommit.replace('SUBS_SOURCE_REF', SOURCE_TAG
                        )
                }
                gitRepoAddTag(repoUrl, repoName, TAG, GIT_CREDENTIALS, repoCommit)
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            throw e
        }
    }
}
