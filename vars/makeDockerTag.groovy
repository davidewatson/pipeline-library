def call(Map defaultVals, String sha) {
  def versionFileContents = readFile(defaultVals.versionfile).trim()
  def verComponents = versionFileContents.split('\\.')

  if (verComponents.length != 3) {
    error "Invalid .versionfile contents: ${versionFileContents}"
  }

  def updatedBuild = isPathChange(defaultVals.versionfile, "${env.CHANGE_ID}") ? 
    verComponents[2].toInteger() : verComponents[2].toInteger() + 1

  if (sha == "") {
    return "${verComponents[0]}.${verComponents[1]}.${Integer.toString(updatedBuild)}"
  } else {
    return "${verComponents[0]}.${verComponents[1]}.${Integer.toString(updatedBuild)}-${sha}"
  }
}