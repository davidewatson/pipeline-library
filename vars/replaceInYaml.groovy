def call(String filePath, String dotPath, String value) {
  sh """
  parse.py --file ${filePath} --keyval ${dotPath}=${value} --dry-run
  """

  sh """
  parse.py --file ${filePath} --keyval ${dotPath}=${value}
  """
}