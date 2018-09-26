def call(Map defaultVals) {
  def versionFileContents = readFile(defaultVals.versionfile).trim()
  def verComponents = versionFileContents.split('\\.')

  if (verComponents.length != 3) {
    error "Invalid .versionfile contents: ${versionFileContents}"
  }

  def updatedBuild = verComponents[2].toInteger() + 1 
  writeFile(file: "${pwd()}/${defaultVals.versionfile}", text: "${verComponents[0]}.${verComponents[1]}.${Integer.toString(updatedBuild)}")
}