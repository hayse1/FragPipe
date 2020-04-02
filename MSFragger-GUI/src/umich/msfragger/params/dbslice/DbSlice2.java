package umich.msfragger.params.dbslice;

import com.dmtavt.fragpipe.api.Bus;
import com.dmtavt.fragpipe.api.ICheck;
import com.dmtavt.fragpipe.api.PyInfo;
import com.dmtavt.fragpipe.exceptions.ValidationException;
import com.dmtavt.fragpipe.messages.NoteDbsliceConfig;
import com.dmtavt.fragpipe.messages.NoteMsfraggerConfig;
import com.dmtavt.fragpipe.messages.NotePythonConfig;
import com.dmtavt.fragpipe.tools.msfragger.MsfraggerVerCmp;
import com.github.chhh.utils.CheckResult;
import com.github.chhh.utils.Installed;
import com.github.chhh.utils.JarUtils;
import com.github.chhh.utils.PythonModule;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import umich.msfragger.params.dbslice.DbSlice.MessageInitDone.REASON;
import umich.msfragger.params.fragger.MsfraggerProps;
import umich.msfragger.params.speclib.SpecLibGen;

public class DbSlice2 {
  private static final Logger log = LoggerFactory.getLogger(DbSlice2.class);
  private static DbSlice2 INSTANCE = new DbSlice2();
  private final Object initLock = new Object();
  public static DbSlice2 get() { return INSTANCE; }
  public static final String DEFAULT_MESSAGE = "Python 3 with numpy, pandas is "
      + "needed for DB Splitting functionality.";

  private static final String UNPACK_SUBDIR_IN_TEMP = "fragpipe";
  private static final String SCRIPT_SPEC_LIB_GEN = "/speclib/gen_con_spec_lib.py";
  private static final String SCRIPT_SPLITTER = "/" + MsfraggerProps.DBSPLIT_SCRIPT_NAME;
  public static final String[] RESOURCE_LOCATIONS = {SCRIPT_SPLITTER};
  public static final PythonModule[] REQUIRED_MODULES = {
      new PythonModule("numpy", "numpy"),
      new PythonModule("pandas", "pandas"),
  };

  private PyInfo pi;
  private Path scriptDbslicingPath;
  private String msfraggerVer;
  private boolean isInitialized;

  /** To be called by top level application in order to initialize
   * the singleton and subscribe it to the bus. */
  public static void initClass() {
    log.debug("Static initialization initiated");
    INSTANCE = new DbSlice2();
    Bus.register(INSTANCE);
  }

  private DbSlice2() {
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
      init(python, fragger);
      Bus.postSticky(new NoteDbsliceConfig(this, null));
    } catch (ValidationException e) {
      Bus.postSticky(new NoteDbsliceConfig(null, e));
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
      if (python == null || fragger == null)
        throw new ValidationException("Both Python and MSFragger need to be configured first.");

      isInitialized = false;

      checkPythonVer();
      checkPythonModules();
      checkFragger(fragger);

      this.pi = python.pi;
      this.msfraggerVer = fragger.version;
      isInitialized = true;
    }
  }

  private void checkPythonModules() throws ValidationException {
    List<Installed> badStatuses = Arrays.stream(Installed.values())
        .filter(installed -> !installed.equals(Installed.YES)).collect(
            Collectors.toList());

    final Map<Installed, String> map = new HashMap<>();
    map.put(Installed.INSTALLED_WITH_IMPORTERROR, "Error loading module");
    map.put(Installed.NO, "Missing");
    map.put(Installed.UNKNOWN, "N/A");

    final List<String> badModsByStatus = new ArrayList<>();
    for (Installed badStatus : badStatuses) {
      List<PythonModule> badMods = createPythonModulesStatusList(badStatus);
      if (!badMods.isEmpty()) {
        String bad = badMods.stream().map(pm -> pm.installName).collect(Collectors.joining(", "))
            + " - " + map.getOrDefault(badStatus, badStatus.name());
        badModsByStatus.add(bad);
      }
    }
    if (!badModsByStatus.isEmpty()) {
      throw new ValidationException("Python modules: " + String.join(", ", badModsByStatus) + ".");
    }

  }

  private void checkPythonVer() throws ValidationException {
    if (pi.getMajorVersion() != 3) {
      throw new ValidationException("Python version 3.x is required");
    }
  }

  private List<PythonModule> createPythonModulesStatusList(Installed installedStatus) {
    return Arrays.stream(REQUIRED_MODULES)
        .filter(pm -> installedStatus.equals(pi.checkModuleInstalled(pm)))
        .collect(Collectors.toList());
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
