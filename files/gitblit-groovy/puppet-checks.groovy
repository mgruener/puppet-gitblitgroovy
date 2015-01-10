/**
 * Gitblit Pre-Receive Hook: puppet-checks
 *
 * Perform the following checks:
 *  - puppet syntax checks
 *  - puppet-lint style checks
 *  - erb template syntax checks
 *  - yaml (hiera data) syntax checks
 *  - json (puppet module metadata) syntax checks
 *
 *  inspired by https://github.com/drwahl/puppet-git-hooks/
 *
 *  gitblit.properties:
 *    # the checks to perform
 *    #   validate -> puppet parser validate
 *    #   lint     -> puppet-lint
 *    #   erb      -> erb syntax check
 *    #   yaml     -> yaml syntax check
 *    #   json     -> json syntax check
 *    puppetchecks.checks = validate lint erb yaml json
 *    # the puppet-lint options to use
 *    # see puppet-lint --help for details
 *    puppetchecks.lintoptions = --fail-on-warnings --no-autoloader_layout-check --no-80chars-check
 *
 *    groovy.customFields = "puppetchecks=Puppet checks (overrides puppetchecks.checks)" "puppetcheckslintoptions=Puppet Lint Options (overrides puppetchecks.lintoptions)"
 *
 *    
 *
 */


import com.gitblit.GitBlit
import com.gitblit.models.RepositoryModel
import com.gitblit.models.UserModel
import com.gitblit.models.PathModel
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceiveCommand.Result
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.api.ArchiveCommand
import org.eclipse.jgit.archive.TarFormat
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.api.Git
import org.slf4j.Logger
import com.gitblit.utils.JGitUtils
import com.gitblit.utils.StringUtils
import com.gitblit.utils.ClientLogger

def indent (text) {
  def doIndent = { it = "  !! " + it }
  text.readLines().collect(doIndent).join('\n') 
}

logger.info("puppet-checks hook triggered by ${user.username} for ${repository.name}: checking ${commands.size} commands")

// check which tools are available
def puppetAvailable = 0
def puppetlintAvailable = 0
def erbAvailable = 0
def rubyAvailable = 0
try {
	// for puppet syntax checks
	def puppet = """puppet --version""".execute()
	puppet.waitFor()
	logger.debug("puppet-checks: puppet version: ${puppet.in.text}")
	puppetAvailable = 1
} catch (IOException e) {
	logger.warn("puppet-checks: puppet not found, skipping puppet syntax checks")
} 

try {
	// for puppet coding style checks
	def puppetlint = """puppet-lint --version""".execute()
	puppetlint.waitFor()
	logger.debug("puppet-checks: puppet-lint version: ${puppetlint.in.text}")
	puppetlintAvailable = 1
} catch (IOException e) {
	logger.warn("puppet-checks: puppet-lint not found, skipping puppet coding style checks")
}

try {
	// for template syntax checks
	def erb = """erb --version""".execute()
	erb.waitFor()
	logger.debug("puppet-checks: erb version: ${erb.in.text}")
	erbAvailable = 1
} catch (IOException e) {
	logger.warn("puppet-checks: erb not found, skipping template syntax checks")
}

try {
	// for yaml (for example hiera data) and json (for example metadata.json) syntax checks
	def ruby = """ruby --version""".execute()
	ruby.waitFor()
	logger.info("puppet-checks: ruby version: ${ruby.in.text}")
	rubyAvailable = 1
} catch (IOException e) {
	logger.warn("puppet-checks: ruby not found, skipping yaml / json syntax checks")
}

def checksToPerform = gitblit.getString('puppetchecks.checks','validate lint erb yaml json').tokenize()
def lintOptions = gitblit.getString('puppetchecks.lintoptions','--fail-on-warnings --no-autoloader_layout-check --no-80chars-check').tokenize()

def puppetChecksField = repository.customFields.puppetchecks
def repoChecksToPerform = puppetChecksField ? puppetChecksField.tokenize() : null

def puppetChecksLintOptionsField = repository.customFields.puppetcheckslintoptions
def repoLintOptions = puppetChecksLintOptionsField ? puppetChecksLintOptionsField.tokenize() : null

if (repoChecksToPerform) {
	checksToPerform = repoChecksToPerform
}

if (repoLintOptions) {
	lintOptions = repoLintOptions
}


def blocked = false
Repository r = gitblit.getRepository(repository.name)
git = new Git(r)

def tempdir
def tempfile

for(command in commands) {
	try {
		// create an export of the source tree at the point of the
		// latest commit in an temporary directory
		try {
			// try to use native method to create a temporary
			// directory. Not all java / groovy versions support this
			// method
			tempdir = File.createTempDir()

			tempdir.createNewFile()
			logger.debug("puppet-checks: tempdir is ${tempdir.absolutePath}")

		//if File.createTempDir() is not available
		} catch (MissingMethodException e) {
			// no "createTempDir" in this installation,
			// resort to shell commands instead
			logger.debug("puppet-checks: File.createTempDir() not available, using fallback mktemp shell command")
			def mktemp = """mktemp -d""".execute()
			mktemp.waitFor()
			tempdir = new File(StringUtils.removeNewlines(mktemp.in.text))
			logger.debug("puppet-checks: tempdir is ${tempdir.absolutePath}")
		}

		tempfile = new File("${tempdir.absolutePath}/${command.newId.name}.tar")
		logger.debug("puppet-checks: using tempfile ${tempfile.absolutePath}")
		tempfile.createNewFile()
		
		// after creating the necessary temporary directory and file
		// we have to perform the actual export
		def output = new FileOutputStream(tempfile.absolutePath)
		ArchiveCommand.registerFormat("tar", new TarFormat())
		ArchiveCommand cmd = git.archive();
		cmd.setTree(command.newId)
		cmd.setFormat('tar')
		cmd.setOutputStream(output)
		cmd.call()
		ArchiveCommand.unregisterFormat("tar")

		// extract the export so that puppet-lint etc. can work on the
		// files
		def untar = """tar -C ${tempdir.absolutePath} -xf ${tempfile.absolutePath}""".execute()
		untar.waitFor()

		// traverse all commits of this command for changed files
		def filesToCheck = []
		def commits = JGitUtils.getRevLog(r, command.oldId.name, command.newId.name).reverse()
		logger.debug("puppet-checks: processing ${commits.size} commits")
		for (commit in commits) {
			def files = JGitUtils.getFilesInCommit(r,commit)
			for (f in files) {
				filesToCheck << f.path
			}
		}

		// do the actual checking but only do it _once_ per file
		// even when it was changed in multiple commits
		logger.info("puppet-checks: checking ${filesToCheck.unique().size} files")
		filesToCheck.unique().each {
			def fileext = it.substring(it.lastIndexOf(".") + 1, it.length())
			switch (fileext.toLowerCase()) {
				case 'pp':
					if (puppetAvailable) {
						def puppet
						if (checksToPerform.contains('validate')) {
							def msg = "puppet-checks: puppet syntax check on file ${it}"
							logger.debug(msg)
							clientLogger.info(msg)
							puppet = ["puppet", "parser", "validate", "--color=false", "${tempdir.absolutePath}/${it}"].execute()
							puppet.waitFor()
						}
						if (checksToPerform.contains('validate') && puppet.exitValue()) {
							msg = "puppet-checks: puppet syntax errors in file ${it}"
							logger.debug(msg)
							clientLogger.error(msg + "\n" + indent(puppet.err.text))
							blocked = true
						} else {
							// only perform puppet-lint checks when the syntax check succeeds
							// (or when puppet validation has been deactivated)
							// puppet-lint would only repeat possible syntax problems
							if (puppetlintAvailable && checksToPerform.contains('lint')) {
								def msg = "puppet-checks: puppet code style check on file ${it}"
								logger.debug(msg)
								clientLogger.info(msg)
								def lintCommand = ["puppet-lint"]
								lintCommand += lintOptions
								lintCommand << "${tempdir.absolutePath}/${it}"
								def puppetlint = lintCommand.execute()
								puppetlint.waitFor()
								if (puppetlint.exitValue()) {
									msg = "puppet-checks: puppet code style errors file ${it}"
									logger.debug(msg)
									clientLogger.error(msg + "\n" + indent(puppetlint.in.text))
									blocked = true
								} else {
									msg = indent(puppetlint.in.text)
									if (msg) {
										clientLogger.info(msg)
									}
								}
							}
						}
					}
					
					break
				case 'erb':
					if (erbAvailable && rubyAvailable && checksToPerform.contains('erb')) {
						def msg = "puppet-checks: template syntax checks on file ${it}"
						logger.debug(msg)
						clientLogger.info(msg)
						def erb = ["erb", "-P", "-x", "-T", "-", "${tempdir.absolutePath}/${it}"].execute()
						def ruby = ["ruby", "-c"].execute()
						erb | ruby
						ruby.waitFor()
						if (ruby.exitValue()) {
							msg = "puppet-checks: template syntax errors file ${it}"
							logger.debug(msg)
							clientLogger.error(msg + "\n" + indent(ruby.err.text))
							blocked = true
						}
					}
					break
				case ['yaml','yml']:
					if (rubyAvailable && checksToPerform.contains('yaml')) {
						def msg = "puppet-checks: yaml syntax checks on file ${it}"
						logger.debug(msg)
						clientLogger.info(msg)
						def ruby = ["ruby","-e","require 'yaml'; YAML.parse_file('${tempdir.absolutePath}/${it}')"].execute()
						ruby.waitFor()
						if (ruby.exitValue()) {
							msg = "puppet-checks: yaml syntax errors file ${it}"
							logger.debug(msg)
							clientLogger.error(msg + "\n" + indent(ruby.err.text))
							blocked = true
						}
					}
					break
				case ['json']:
					if (rubyAvailable && checksToPerform.contains('json')) {
						def msg = "puppet-checks: json syntax checks on file ${it}"
						logger.debug(msg)
						clientLogger.info(msg)
						def ruby = ["ruby","-e","require 'json'; JSON.parse(File.read('${tempdir.absolutePath}/${it}'))"].execute()
						ruby.waitFor()
						if (ruby.exitValue()) {
							msg = "puppet-checks: json syntax errors file ${it}"
							logger.debug(msg)
							clientLogger.error(msg + "\n" + indent(ruby.err.text))
							blocked = true
						}
					}
					break
				default:
					break
			}
		}
		if (blocked) {
			command.setResult(Result.REJECTED_OTHER_REASON, "puppet-checks: some checks failed, declining push.")
		}
	} catch (Throwable e)  {
		// block the current command in case _anything_ goes wrong
		// I do not want pushes to get through unchecked because of
		// some kind of unforseen situation
		command.setResult(Result.REJECTED_OTHER_REASON, "puppet-checks: a server-side exception occurred! Reason: ${e}")
		blocked = true
		throw e
	} finally {
		// using .deleteOnExit() does not work here because it
		// only deletes the files when the VM exists...which would be
		// when gitblit itself stops. We have to clean up ourselfs
		// at the end of the script
		logger.info("puppet-checks: cleaning up")
		if (tempdir) {
			logger.debug("puppet-checks: deleting ${tempdir.absolutePath}")
			tempdir.deleteDir()
		}
	}
}

git.repository.close()

if (blocked) {
	// return false to break the push hook chain
	return false
}
