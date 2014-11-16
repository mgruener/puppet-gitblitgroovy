/**
 * Gitblit Post-Receive Hook: r10k-deploy
 *
 * Perform an r10k deploy environment/module
 *
 */

import com.gitblit.GitBlit
import com.gitblit.models.RepositoryModel
import com.gitblit.models.UserModel
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceiveCommand.Result
import org.slf4j.Logger

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import com.gitblit.utils.JGitUtils
import com.gitblit.utils.StringUtils

logger.info("r10k-deploy hook triggered by ${user.username} for ${repository.name}: checking ${commands.size} commands")

def failed = false
def r10kCommand = gitblit.getString('r10kdeploy.command','sudo -u puppet r10k')
def deployType = repository.customFields.r10kdeploytype
if (!deployType) deployType = 'module'

try {
	def r10k = """${r10kCommand} version""".execute()
	r10k.waitFor()
	logger.debug("r10k-deploy: r10k version: ${r10k.in.text}")
} catch (IOException e) {
	logger.error("r10k-deploy: Unable to execute '${r10kCommand}''")
	return false
}

Repository repo = gitblit.getRepository(repository.name)

for (command in commands) {
	try {
		def ref = command.refName
		def branch = repo.shortenRefName(command.refName)
		// only work on branches because r10k ignores everything else
		if (ref.startsWith('refs/heads/')) {
			def doPuppetfile = ''
			if (deployType != 'module') {
				// if one of the commits of this command changed a file called "Puppetfile"
				// we want r10k to reload the modules of this environment. If not, we can
				// omit the --puppetfile parameter which results in a much faster r10k run
				def commits = JGitUtils.getRevLog(repo, command.oldId.name, command.newId.name).reverse()
				logger.debug("r10k-deploy: processing ${commits.size} commits")
				for (commit in commits) {
				        def commitedFiles = JGitUtils.getFilesInCommit(repo,commit)
				        for (file in commitedFiles) {
				                if (file.path.endsWith('Puppetfile')) {
							doPuppetfile = '--puppetfile'
							break
						}
				        }
					if (doPuppetfile != '') break
				}
			}

			def deployCommand
			if (deployType == 'module') {
				def modName = StringUtils.stripFileExtension(repository.name.split('-').max())
				logger.info("r10k-deploy: deploying module ${modName}")
				deployCommand = "${r10kCommand} deploy module ${modName}"
			} else {
				logger.info("r10k-deploy: deploying environment ${branch}")
				deployCommand = "${r10kCommand} deploy environment ${doPuppetfile} ${branch}"
			}
			def r10kdeploy = """${deployCommand}""".execute()
			r10kdeploy.waitFor()
			if (r10kdeploy.exitValue()) {
				logger.error("r10k-deploy: an error occured during deployment! Reason ${r10kdeploy.err.text}")
			}
		} else {
			logger.debug("r10k-deploy: ${branch} (ref: ${ref}) is not a branch, not deploying")
		}
	} catch (Throwable e) {
		logger.error("r10k-deploy: an exception occured during deployment! Reason ${e}")
		failed = true
	}
}

repo.close()

if (failed) {
        // return false to break the push hook chain
	return false
}
