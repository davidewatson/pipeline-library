def call(Map defaultVals) {
    checkout scm
    result = sh (script: "git log -1 | grep '.*\\[${defaultVals.ciSkip}\\].*'", returnStatus: true)
    currentBuild.result = 'SUCCESS'
    return (result == 0)
}