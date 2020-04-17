package com.dmtavt.fragpipe.cmd;

import com.dmtavt.fragpipe.Fragpipe;
import com.dmtavt.fragpipe.params.speclib.SpeclibPanel;
import com.dmtavt.fragpipe.tabs.TabWorkflow;
import java.awt.Component;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;
import org.apache.commons.io.FilenameUtils;
import com.dmtavt.fragpipe.api.InputLcmsFile;
import com.dmtavt.fragpipe.api.LcmsFileGroup;
//import umich.msfragger.gui.MsfraggerGuiFrame;
import com.dmtavt.fragpipe.params.speclib.SpecLibGen;
import com.github.chhh.utils.PythonInfo;
import com.github.chhh.utils.UsageTrigger;

public class CmdSpecLibGen extends CmdBase {

  public static final String NAME = "SpecLibGen";

  public CmdSpecLibGen(boolean isRun, Path workDir) {
    super(isRun, workDir);
  }

  @Override
  public String getCmdName() {
    return NAME;
  }

  public boolean configure(Component comp, UsageTrigger usePhilosopher, Path jarFragpipe,
      Map<LcmsFileGroup, Path> mapGroupsToProtxml, String fastaPath, boolean isRunProteinProphet, boolean useEasypqp) {

    pbis.clear();
    final SpecLibGen slg = SpecLibGen.get();
    if (!slg.isInitialized()) {
      JOptionPane.showMessageDialog(comp,
          "Spectral Library Generation scripts did not initialize correctly.",
          "Spectral Library Generation Error", JOptionPane.ERROR_MESSAGE);
      return false;
    }

    final String[] compatibleExts = useEasypqp ? new String[]{".d", ".mzml", ".mzxml"} : new String[]{".mzml", ".mzxml"};
    final Predicate<String> isFileCompatible = fn -> Arrays.stream(compatibleExts).anyMatch(ext -> fn.toLowerCase().endsWith(ext));

    boolean isIncompatibleInputs = mapGroupsToProtxml.keySet().stream()
        .flatMap(g -> g.lcmsFiles.stream())
        .anyMatch(lcms -> isFileCompatible.negate().test(lcms.getPath().getFileName().toString()));
    if (isIncompatibleInputs) {
      JOptionPane.showMessageDialog(comp, String.format(
          "<html>Spectral library generation with %s is currently only<br/>\n"
              + "compatible with %s input files.<br/>\n"
              + "You can convert your data using Msconvert program from ProteoWizard.",
          useEasypqp ? "EasyPQP" : "SpectraST", String.join(", ", compatibleExts)),
          "Incompatible input data", JOptionPane.WARNING_MESSAGE);
      return false;
    }


    if (mapGroupsToProtxml.size() > 1) {
      int res = JOptionPane.showConfirmDialog(comp,
          "<html>You have more than 1 experiment/group and spectral<br/>"
              + "library generation is turned on. In that case a separate<br/>"
              + "spectral library is created for each group.<br/><br/>"
              + "<b>Select Yes</b> to continue.<br/><br/>"
              + "<b>Select No</b> if you want a single spectral library generated<br/>"
              + "from ALL input files.<br/>"
              + "Change Experiment/Group configuration on LCMS files tab.<br/>"
              + "E.g. press the <i>Clear Experiments</i> button there.",
          "SpecLibGen config warning", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
      if (JOptionPane.YES_OPTION != res) {
        return false;
      }
    }

    for (Entry<LcmsFileGroup, Path> e : mapGroupsToProtxml.entrySet()) {
      final LcmsFileGroup group = e.getKey();
      final Path protxml = e.getValue();
      final Path groupWd = group.outputDir(wd);

      if (!isRunProteinProphet && !Files.exists(protxml)) {
        JOptionPane.showMessageDialog(comp,
            "Protein Prophet not selected and the output directory:\n"
                + "    " + groupWd.toString() + "\n"
                + "does not contain a '" + protxml.getFileName().toString() + "' file.\n\n"
                + "Either uncheck Spectral Library Generation checkbox or enable Protein Prophet.",
            "Spec Lib Gen configuration Error", JOptionPane.ERROR_MESSAGE);
        return false;
      }

      // for current implementation of speclibgen scripts mzml files need to be
      // located next to pepxml files
      final List<ProcessBuilder> pbsDeleteLcmsFiles = new ArrayList<>();
      for (InputLcmsFile lcms : group.lcmsFiles) {
        final String fn_sans_extension = useEasypqp ?
                FilenameUtils.removeExtension(lcms.getPath().getFileName().toString()) : null;
        final String fn = lcms.getPath().getFileName().toString();
        final boolean isTimsTOF = !(fn.toLowerCase().endsWith(".mzml") || fn.toLowerCase().endsWith(".mzxml"));
        final Path lcms_path = useEasypqp && isTimsTOF ?
                lcms.getPath().getParent().resolve(fn_sans_extension + "_calibrated.mgf") :
                lcms.getPath();
        if (!useEasypqp)
          if (!groupWd.equals(lcms_path.getParent())) {
            final Path copy = groupWd.resolve(lcms_path.getFileName());
            if (!Files.exists(copy)) {
              // Directory of LCMS file is different from pepxml file
              // and the file does not yet exist.
              // Copy over the file and schedule for deletion.
              List<ProcessBuilder> pbCopy = ToolingUtils
                      .pbsCopyFiles(jarFragpipe, groupWd, Collections.singletonList(lcms_path));
              pbis.addAll(PbiBuilder.from(pbCopy));
              pbsDeleteLcmsFiles.addAll(ToolingUtils
                      .pbsDeleteFiles(jarFragpipe, Collections.singletonList(groupWd.resolve(lcms_path.getFileName()))));
            }
          }
      }

      List<String> cmd = new ArrayList<>();
      cmd.add(slg.getPi().getCommand());
      cmd.add("-u"); // PYTHONUNBUFFERED: when mixing subprocess output with Python output, use this to keep the outputs in order
      cmd.add(slg.getScriptSpecLibGenPath().toString());
      if (useEasypqp) {
        /**
         * See https://github.com/grosenberger/easypqp on how to install EasyPQP
         * EasyPQP needs the following placed in the pep xml directory
         * - MGFs or mzMLs
         * - interact.pep.xml
         * - peptide.tsv, psm.tsv from Philosopher
         * */
        cmd.add(fastaPath);
        cmd.add(groupWd.toString()); // this is "Pep xml directory"
        cmd.add(group.lcmsFiles.stream().map(InputLcmsFile::getPath).map(Path::toString).collect(Collectors.joining(File.pathSeparator))); // lcms files
        cmd.add(groupWd.toString()); // output directory
        cmd.add("True"); // overwrite (true/false), optional arg
        cmd.add("usePhilosopher.useBin()"); // philosopher binary path (not needed for easyPQP)
        cmd.add("use_easypqp"); // philosopher binary path (not needed for easyPQP)

        TabWorkflow tabWorkflow = Fragpipe.getStickyStrict(TabWorkflow.class);
        SpeclibPanel speclibPanel = Fragpipe.getStickyStrict(SpeclibPanel.class);

        final String cal = speclibPanel.getEasypqpCalOption();
        final Path calTsvPath = speclibPanel.getEasypqpCalFilePath();
        cmd.add(cal.equals("a tsv file") ? calTsvPath.toString() : cal); // retention time alignment options
        cmd.add(String.valueOf(tabWorkflow.getThreads()));

        speclibPanel.getEasypqpFileType(); // TODO: GUOCI - this statement was dangling alone here without any assignment or method call

      } else {
        cmd.add(fastaPath);
        cmd.add(groupWd.toString()); // this is "Pep xml directory"
        cmd.add(protxml.toString()); // protxml file
        cmd.add(groupWd.toString()); // output directory
        cmd.add("True"); // overwrite (true/false), optional arg
        cmd.add(usePhilosopher.useBin()); // philosopher binary path (optional)
      }
      ProcessBuilder pb = new ProcessBuilder(cmd);
      PythonInfo.modifyEnvironmentVariablesForPythonSubprocesses(pb);
      pb.directory(groupWd.toFile());
      pb.environment().put("PYTHONIOENCODING", "utf-8");

      pbis.add(PbiBuilder.from(pb));
      pbis.addAll(PbiBuilder.from(pbsDeleteLcmsFiles));
    }

    isConfigured = true;
    return true;
  }

  @Override
  public int getPriority() {
    return 1000;
  }
}