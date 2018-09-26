def call(Map defaultVals) {
    result = sh (script: "git log -1 | grep '.*\\[${defaultVals.ciSkip}\\].*'", returnStatus: true)
    currentBuild.result = 'SUCCESS'
    return (result == 0)
}