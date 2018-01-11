/**
 * Git merge branches pipeline
 * REPO_URL - Repository URL
 * TARGET_BRANCH - Target branch for merging
 * SOURCE_BRANCH - The branch will be merged to TARGET_BRANCH
 * CREDENTIALS_ID - Used credentails ID
 *
**/

def common = new com.mirantis.mk.Common()
def git = new com.mirantis.mk.Git()
timeout(time: 12, unit: 'HOURS') {
  node {
    try{
      stage("checkout") {
        git.checkoutGitRepository('repo', REPO_URL, TARGET_BRANCH, IMAGE_CREDENTIALS_ID)
      }
      stage("merge") {
        dir("repo"){
          sh("git fetch origin/${SOURCE_BRANCH} && git merge ${SOURCE_BRANCH} && git push origin ${TARGET_BRANCH}")
        }
      }
    } catch (Throwable e) {
       // If there was an error or exception thrown, the build failed
       currentBuild.result = "FAILURE"
       currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
       throw e
    }
  }
}

