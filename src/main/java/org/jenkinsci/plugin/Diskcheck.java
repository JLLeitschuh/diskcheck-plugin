package org.jenkinsci.plugin;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.Descriptor;
import hudson.model.Computer;
import hudson.tasks.BuildWrapper;
import hudson.node_monitors.*;
import hudson.model.Node;
import hudson.model.Result;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;
import hudson.util.RemotingDiagnostics;

import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

import hudson.node_monitors.DiskSpaceMonitorDescriptor;



/**
 * Class to allow any build step to be performed before the SCM checkout occurs.
 * 
 * @author Manoj Thakkar
 * 
 */


public class Diskcheck extends BuildWrapper {


	public final boolean failOnError;

	/**
	 * Constructor taking a list of buildsteps to use.
	 * 
	 * @param buildstep
	 *            list of but steps configured in the UI
	 */
	@DataBoundConstructor
	public Diskcheck(boolean failOnError) {
		this.failOnError = failOnError;
	}

	/**
	 * Overridden setup returns a noop class as we don't want to add annything
	 * here.
	 * 
	 * @param build
	 * @param launcher
	 * @param listener
	 * @return noop Environment class
	 */
	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		return new NoopEnv();
	}

	/**
	 * Overridden precheckout step, this is where wedo all the work.
	 * 
	 * Checks to make sure we have some buildsteps set, and then calls the
	 * prebuild and perform on all of them.
	 * 
	 * @todo handle build steps failure in some sort of reasonable way
	 * 
	 * @param build
	 * @param launcher
	 * @param listener
	 */

	@Override
	public Descriptor getDescriptor() {
		return (Descriptor) super.getDescriptor();
	}



	@Override
	public void preCheckout(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {
		PrintStream log = listener.getLogger();
		// Default value of disk space check is 1Gb		
		int SpaceThreshold;
		SpaceThreshold = PluginImpl.getInstance().getSpacecheck();


		log.println("Disk space threshold is set to :" + SpaceThreshold + "Gb");
		log.println("Checking disk space Now ");

		/* touch workspace so that it is created on first time */
		if (!build.getWorkspace().exists()) {
			build.getWorkspace().mkdirs();
		}

		Node node1 = build.getBuiltOn();

		Computer Comp = node1.toComputer();

		String NodeName = build.getBuiltOnStr();

		int roundedSize=0;
		try 
		{
			if ( Comp != null) {

				String baseName = build.getWorkspace().getBaseName();
				String buildWorkSpace = build.getWorkspace().toString();
				String buildScript = String.format("new File(\"%s\").getFreeSpace()",buildWorkSpace);

				String diskSpace = RemotingDiagnostics.executeGroovy(buildScript, Comp.getChannel());
				diskSpace = diskSpace.split(":")[diskSpace.split(" ").length-1].replaceAll("\\s+","");
				// If we can not get the disk space from remote diagnostic we shall use the diskcheck as a backup
				log.println("diskspace is "+ diskSpace);
				if ( diskSpace == null) {
					if ( DiskSpaceMonitor.DESCRIPTOR.get(Comp)== null )
					{   log.println("No Slave Data available trying to get data from slave");
					Thread.sleep(1000);
					if ( DiskSpaceMonitor.DESCRIPTOR.get(Comp)== null )

						log.println(" Could not get Slave Information , Exiting Disk check for this slave");
					return;
					}
					AbstractDiskSpaceMonitor.getAll();
					long size = DiskSpaceMonitor.DESCRIPTOR.get(Comp).size;
					DiskSpaceMonitorDescriptor.DiskSpace diskSpaceMonitor = new DiskSpaceMonitorDescriptor.DiskSpace(baseName, size);
					diskSpace= diskSpaceMonitor.getGbLeft();
				}
				log.println ( "Total Disk space in workspace is "+ diskSpace);

				roundedSize = (int) (Long.parseLong(diskSpace) / (1024 * 1024 * 1024));
				//roundedSize = (int) (size / (1024 * 1024 * 1024));
			}
		}
		catch(NullPointerException e ){
			log.println("Could not get Slave disk size Information , Exiting Disk check for this slave");
			return;
		}
		log.println("Total Disk Space Available is: " + roundedSize + "Gb");

		if (build.getBuiltOnStr() == "") {
			NodeName = "master";
		}

		log.println(" Node Name: " + NodeName);

		if (PluginImpl.getInstance().isDiskrecyclerenabled()) {
			if (roundedSize < SpaceThreshold) {
				log.println("Disk Recycler is Enabled, wipe off the workspace Directory Now ");
				//TODO change this to a stringBuilder
				String myShellCommand = " "
						+ "echo ${WORKSPACE}; "
						+ "if [ $(basename $(dirname `pwd`)) = 'workspace' ];then "
						+   " cd $(dirname ${WORKSPACE}) ;"
						+ 	  "find * -maxdepth 1 -type d ! -name $(basename ${WORKSPACE}) -delete ;"
						+ "else "
						+ "echo Could not delete Workspace completly ;"
						+ "true ;"
						+ "fi; "
						+ "df -h .; "
						+ "cd ${WORKSPACE} ";

				String myWinCommand = "echo Deleting file from %WORKSPACE% && Del /R %WORKSPACE%";
				/**
				 * This method will return the command intercepter as per the
				 * node OS
				 * 
				 * @param launcher
				 * @param script
				 * @return CommandInterpreter
				 */
				CommandInterpreter runscript;
				if (launcher.isUnix())
					runscript = new Shell(myShellCommand);
				else
					runscript = new BatchFile(myWinCommand);

				Result result = runscript.perform(build, launcher, listener) ? Result.SUCCESS
						: Result.FAILURE;

				if (result.toString() == "FAILURE") {
					throw new AbortException(
							"Something went wrong while deleting Files , Please check the error message above");
				}
			}
		}

		log.println("Running Prebuild steps");
		if (roundedSize < SpaceThreshold
				&& !(PluginImpl.getInstance().isDiskrecyclerenabled())) {
			throw new AbortException(
					"Disk Space is too low please look into it before starting a build");

		}
	}


	@Extension
	public static final class DescriptorImpl extends Descriptor<BuildWrapper> {

		/**
		 * This human readable name is used in the configuration screen.
		 */
		public String getDisplayName() {
			return "Enable Disk Check";
		}



		public DescriptorImpl() {
			load();
		}


	}


	class NoopEnv extends Environment {
	}
}
