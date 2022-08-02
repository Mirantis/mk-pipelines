salt = new com.mirantis.mk.Salt()
python = new com.mirantis.mk.Python()
common = new com.mirantis.mk.Common()
venvPepper = "venvPepper"
upgradeChecks = new com.mirantis.mcp.UpgradeChecks()

reportDir = 'reportDir/'
waList =['check_34406', 'check_34645', 'check_35705', 'check_35884', 'check_36461', 'check_36461_2']

timeout(time: PIPELINE_TIMEOUT, unit: 'HOURS') {
    node('python') {
        try {
            python.setupPepperVirtualenv(venvPepper, SALT_MASTER_URL, SALT_MASTER_CREDENTIALS)
            def clusterName = salt.getPillar(venvPepper, 'I@salt:master', "_param:cluster_name").get("return")[0].values()[0]
            def reportContent = ""
            stage('Start checking work-arounds') {
                for (wa in waList) {
                    //false - return status of check, true - raise exception
                    def waData = upgradeChecks."$wa"(salt, venvPepper, clusterName, false)
                    def reportTemplate = """
      <tr>
        <td class='row'>${waData.prodId}</td>
        <td class='row'>${waData.isFixed}</td>
        <td class='row'>${waData.waInfo}</td>
      </tr>"""
                    reportContent = "${reportContent}${reportTemplate}"
                }
            }
            stage('Generate report') {
                sh "rm -rf ${reportDir}"
                sh "mkdir -p ${reportDir}"
                def reportHead = """<html>
  <head>
    <title>WA verify report</title>
  </head>
  <body>
    <h1>WA verify report</h1>
    <table border='1' cellpadding='5' cellspacing='0' style='border-collapse:collapse'>
      <tr>
        <th class='row'>Prod id</th>
        <th class='row'>Status</th>
        <th class='row'>Comment</th>
      </tr>"""
                def reportTail = """
    </table>
  </body>
</html>"""
                writeFile file: "${reportDir}report.html", text: "${reportHead}${reportContent}${reportTail}"
                archiveArtifacts artifacts: "${reportDir}/*"
            }
        } catch (Throwable e) {
            // If there was an error or exception thrown, the build failed
            currentBuild.result = "FAILURE"
            currentBuild.description = currentBuild.description ? e.message + " " + currentBuild.description : e.message
            throw e
        }
    }
}
