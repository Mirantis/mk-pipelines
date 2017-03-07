def branches = [:]

for (cluster in TEST_MODELS.split(',')) {
  branches["${cluster}"] = {
    build job: "test-salt-model-${cluster}", parameters: [
      [$class: 'StringParameterValue', name: 'RECLASS_SYSTEM_GIT_URL', value: "${GERRIT_SCHEME}://${GERRIT_NAME}@${GERRIT_HOST}:${GERRIT_PORT}/${GERRIT_PROJECT}.git"],
      [$class: 'StringParameterValue', name: 'RECLASS_SYSTEM_GIT_REF', value: GERRIT_REFSPEC]
    ]
  }
}

parallel branches
