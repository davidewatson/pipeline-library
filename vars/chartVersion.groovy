def call(Map defaultVals, String packageName, String preReleaseInfo, String sha) {
  def versionFileContents = readFile(defaultVals.versionfile).trim()
  def versionFileComponents = versionFileContents.split('\\.')

  if (versionFileComponents.length != 3) {
    error "Invalid .versionfile contents: ${versionFileContents}"
  }

  def updatedBuild = versionFileComponents[2].toInteger() + 1 

  // load chart yaml
  def chartYaml = parseYaml(readFile("${pwd()}/${chartLocation(defaultVals, packageName)}/Chart.yaml"))

  if (preReleaseInfo != "") {
    if (sha == "") {
      error "Git SHA must be specified!"
    }

    def chartVersionComponents = []
    chartVersionComponents.addAll(chartYaml.version.toString().split('\\+'))

    if (chartVersionComponents.size() > 1) {
      chartVersionComponents[1] = sha
    } else {
      chartVersionComponents << sha
    }

    chartVersionComponents[0] = "${versionFileComponents[0]}.${versionFileComponents[1]}.${Integer.toString(updatedBuild)}-${preReleaseInfo}"

    chartYaml.version = chartVersionComponents.join('+')
  } else {
    chartYaml.version = "${versionFileComponents[0]}.${versionFileComponents[1]}.${Integer.toString(updatedBuild)}"
  }

  toYamlFile(chartYaml, "${pwd()}/${chartLocation(defaultVals, packageName)}/Chart.yaml")

  // stash the Chart.yaml
  stash(
    name: "${packageName}-chartyaml-${env.BUILD_ID}".replaceAll('-','_'),
    includes: "${chartLocation(defaultVals, packageName)}/Chart.yaml"
  )

  return chartYaml.version
}