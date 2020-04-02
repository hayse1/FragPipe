package umich.msfragger.params.dbslice;

import com.dmtavt.fragpipe.api.Bus;
import com.dmtavt.fragpipe.api.PyInfo;
import com.dmtavt.fragpipe.exceptions.ValidationException;
import com.dmtavt.fragpipe.messages.NoteDbsplitConfig;
import com.dmtavt.fragpipe.messages.NoteMsfraggerConfig;
import com.dmtavt.fragpipe.messages.NotePythonConfig;
import com.dmtavt.fragpipe.tools.msfragger.MsfraggerVerCmp;
import com.github.chhh.utils.Installed;
import com.github.chhh.utils.JarUtils;
import com.github.chhh.utils.PythonModule;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import umich.msfragger.params.fragger.MsfraggerProps;
import umich.msfragger.params.speclib.SpecLibGen;

public class DbSplit2 {
  private static final Logger log = LoggerFactory.getLogger(DbSplit2.class);
  private static DbSplit2 INSTANCE = new DbSplit2();
  private final Object initLock = new Object();
  public static DbSplit2 get() { return INSTANCE; }
  public static final String DEFAULT_MESSAGE = "Python 3 with numpy, pandas is "
      + "needed for DB Splitting functionality.";

  private static final String UNPACK_SUBDIR_IN_TEMP = "fragpipe";
  private static final String SCRIPT_SPEC_LIB_GEN = "/speclib/gen_con_spec_lib.py";
  private static final String SCRIPT_SPLITTER = "/" + MsfraggerProps.DBSPLIT_SCRIPT_NAME;
  public static final String[] RESOURCE_LOCATIONS = {SCRIPT_SPLITTER};
  public static final List<PythonModule> REQUIRED_MODULES = Arrays.asList(PythonModule.NUMPY, PythonModule.PANDAS);

  private PyInfo pi;
  private Path scriptDbslicingPath;
  private String msfraggerVer;
  private boolean isInitialized;

  /** To be called by top level application in order to initialize
   * the singleton and subscribe it to the bus. */
  public static void initClass() {
    log.debug("Static initialization initiated");
    INSTANCE = new DbSplit2();
    Bus.register(INSTANCE);
  }

  private DbSplit2() {
    pi = null;
    scriptDbslicingPath = null;
    msfraggerVer = null;
    isInitialized = false;
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  public void onPythonConfig(NotePythonConfig m) {
    onPythonOrFraggerChange(m, Bus.getStickyEvent(NoteMsfraggerConfig.class));
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  public void onMsfraggerConfig(NoteMsfraggerConfig m) {
    onPythonOrFraggerChange(Bus.getStickyEvent(NotePythonConfig.class), m);
  }

  private void onPythonOrFraggerChange(NotePythonConfig python, NoteMsfraggerConfig fragger) {
    try {
      log.debug("Started init of: {}, python null={}, fragger null={}", DbSplit2.class.getSimpleName(),
          python == null, fragger == null);
      init(python, fragger);
      Bus.postSticky(new NoteDbsplitConfig(this, null));
    } catch (ValidationException e) {
      Bus.postSticky(new NoteDbsplitConfig(null, e));
    }
  }

  public Path getScriptDbslicingPath() {
    return scriptDbslicingPath;
  }

  public PyInfo getPythonInfo() {
    return pi;
  }

  public boolean isInitialized() {
    synchronized (initLock) {
      return isInitialized;
    }
  }

  private void init(NotePythonConfig python, NoteMsfraggerConfig fragger) throws ValidationException {
    synchronized (initLock) {
      if (python == null || python.pi == null || fragger == null || fragger.version == null)
        throw new ValidationException("Both Python and MSFragger need to be configured first.");
      isInitialized = false;

      checkPython(python);
      checkFragger(fragger);
      unpack();

      isInitialized = true;
      log.debug("{} init complete",DbSplit2.class.getSimpleName());
    }
  }

  private void checkPython(NotePythonConfig m) throws ValidationException {
    checkPythonVer(m);
    checkPythonModules(m.pi);
    this.pi = m.pi;
  }

  private void checkPythonModules(PyInfo pi) throws ValidationException {
    Map<Installed, List<PythonModule>> modules = pi.modulesByStatus(REQUIRED_MODULES);
    final Map<Installed, String> bad = new LinkedHashMap<>();
    bad.put(Installed.NO, "Missing");
    bad.put(Installed.INSTALLED_WITH_IMPORTERROR, "Error loading module");
    bad.put(Installed.UNKNOWN, "N/A");

    if (modules.keySet().stream().anyMatch(bad::containsKey)) {
      final List<String> byStatus = new ArrayList<>();
      for (Installed status : bad.keySet()) {
        List<PythonModule> list = modules.get(status);
        if (list != null) {
          byStatus.add(bad.get(status) + " - " + list.stream().map(pm -> pm.installName).collect(Collectors.joining(", ")));
        }
      }
      throw new ValidationException("Python modules: \n" + String.join("\n", byStatus));
    }

    this.pi = pi;
  }

  private void checkPythonVer(NotePythonConfig m) throws ValidationException {
    if (m.pi == null || m.pi.getMajorVersion() != 3) {
      throw new ValidationException("Python version 3.x is required");
    }
  }

  private void unpack() throws ValidationException {
    for (String rl : RESOURCE_LOCATIONS) {
      Path subDir = Paths.get(UNPACK_SUBDIR_IN_TEMP);
      Path path = null;
      try {
        path = JarUtils.unpackFromJar(SpecLibGen.class, rl, subDir, true, true);
      } catch (IOException e) {
        throw new ValidationException("Could not unpack DbSlice", e);
      }
      // record the location of the main script that we'll be running
      if (SCRIPT_SPLITTER.equals(rl))
        scriptDbslicingPath = path;
    }
  }

  private void checkFragger(NoteMsfraggerConfig m) throws ValidationException {
    if (!m.isValid()) {
      throw new ValidationException("Require valid MSFragger");
    }
    final String minFraggerVer = MsfraggerProps.getProperties()
        .getProperty(MsfraggerProps.PROP_MIN_VERSION_SLICING, "20180924");
    if (MsfraggerVerCmp.get().compare(m.version, minFraggerVer) < 0)
      throw new ValidationException("Minimum MSfragger version required: " + minFraggerVer);
  }
}