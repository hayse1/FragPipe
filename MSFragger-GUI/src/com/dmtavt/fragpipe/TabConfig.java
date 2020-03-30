package com.dmtavt.fragpipe;

import static com.dmtavt.fragpipe.Fragpipe.fe;

import com.dmtavt.fragpipe.api.BalloonTips;
import com.dmtavt.fragpipe.api.Bus;
import com.dmtavt.fragpipe.exceptions.ValidationException;
import com.dmtavt.fragpipe.messages.MessageBalloon;
import com.dmtavt.fragpipe.messages.MessageClearCache;
import com.dmtavt.fragpipe.messages.MessageMsfraggerNewBin;
import com.dmtavt.fragpipe.messages.MessageMsfraggerUpdateAvailable;
import com.dmtavt.fragpipe.messages.MessageShowAboutDialog;
import com.dmtavt.fragpipe.messages.MessageUiStateLoaded;
import com.dmtavt.fragpipe.messages.MessageUmpireEnabled;
import com.dmtavt.fragpipe.messages.NoteConfigMsfragger;
import com.dmtavt.fragpipe.tools.msfragger.Msfragger;
import com.dmtavt.fragpipe.tools.msfragger.Msfragger.Version;
import com.github.chhh.utils.JarUtils;
import com.github.chhh.utils.StringUtils;
import com.github.chhh.utils.swing.FileChooserUtils;
import com.github.chhh.utils.swing.FileChooserUtils.FcMode;
import com.github.chhh.utils.swing.FormEntry;
import com.github.chhh.utils.swing.UiCheck;
import com.github.chhh.utils.swing.UiText;
import com.github.chhh.utils.swing.UiUtils;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.miginfocom.layout.CC;
import net.miginfocom.layout.LC;
import net.miginfocom.swing.MigLayout;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import umich.msfragger.params.ThisAppProps;
import com.github.chhh.utils.SwingUtils;
import com.github.chhh.utils.swing.JPanelWithEnablement;
import umich.msfragger.params.fragger.MsfraggerProps;

public class TabConfig extends JPanelWithEnablement {
  private static final Logger log = LoggerFactory.getLogger(TabConfig.class);

  private UiText uiTextBinFragger;
  private JEditorPane epFraggerVer;
  public static final String TIP_MSFRAGGER_BIN = "tip.msfragger.bin";
  public static final String PREFIX_CONFIG = "fragpipe-config.";

  public TabConfig() {
    init();
    initMore();
  }

  private void initMore() {
    Bus.register(this);
  }

  private void init() {
    this.setLayout(new MigLayout(new LC().fillX()));
    add(createPanelTopButtons(), new CC().growX().wrap());
    add(createPanelFragger(), new CC().growX().wrap());

    {
      JLabel c = new JLabel();
      c.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
      c.setAlignmentX(Component.CENTER_ALIGNMENT);
      c.setText(SwingUtils.makeHtml(
          "Tabs on top represent processing steps and will be performed sequentially.\n"
              + "Tabs will become enabled once the tools on this panel are configured."));
      add(c, new CC().growX().wrap());
    }

    {
      Properties props = ThisAppProps.getRemotePropertiesWithLocalDefaults();
//      Properties props = ThisAppProps.getLocalProperties(); // for testing
      String linkUrl = props.getProperty(ThisAppProps.PROP_SETUP_TUTORIAL_URL,
          "https://msfragger.nesvilab.org/tutorial_setup_fragpipe.html");

      JEditorPane c = SwingUtils.createClickableHtml(
          "<a href='" + linkUrl + "'>Configuration Help</a>");
      c.setAlignmentX(Component.CENTER_ALIGNMENT);
      JPanel p = new JPanel();
      p.setAlignmentX(Component.CENTER_ALIGNMENT);
      p.add(c);
      add(p, new CC().growX().wrap());
    }
  }

  private JPanel createPanelTopButtons() {
    JPanel p = newMigPanel();
    Supplier<CC> ccL = () -> new CC().alignX("left");
    Supplier<CC> ccR = () -> new CC().alignX("right");
    p.add(UiUtils.createButton("About", e -> Bus.post(new MessageShowAboutDialog())), ccL.get().split().spanX());
    p.add(UiUtils.createButton("Clear Cache", e -> Bus.post(new MessageClearCache())), ccL.get());
    UiCheck uiCheckUmpire = UiUtils.createUiCheck("Enable DIA-Umpire", false,
        e -> Bus.post(new MessageUmpireEnabled(((JCheckBox) e.getSource()).isSelected())));
    p.add(uiCheckUmpire, ccL.get().wrap());
    //p.add(UiUtils.createButton("Find tools", e -> post(new MessageFindTools())), ccL.get().split().spanX());
    JLabel label = new JLabel("Main tools configuration");
    label.setFont(new Font(label.getFont().getName(), Font.BOLD, label.getFont().getSize()+3));
    p.add(label, ccL.get().wrap());
    return p;
  }

  private JPanel createPanelFragger() {
    JPanel p = newMigPanel();
    p.setBorder(new TitledBorder("MSFragger"));

    final String binMsfraggerTip = "Select path to MSFragger.jar";
    uiTextBinFragger = UiUtils.uiTextBuilder().ghost(binMsfraggerTip).create();
    FormEntry feBinMsfragger = fe(uiTextBinFragger, "bin-msfragger", PREFIX_CONFIG)
        .tooltip(binMsfraggerTip).create();
    p.add(feBinMsfragger.comp, ccL().split().growX());

    JButton btnBrowse = feBinMsfragger
        .browseButton("Browse", this::createBinMsfraggerFilechooser, binMsfraggerTip, paths -> {
          paths.stream().findFirst().ifPresent(jar -> Bus.post(new MessageMsfraggerNewBin(jar.toString())));
        });
    p.add(btnBrowse, ccL());
    JButton btnUpdate = UiUtils.createButton("Update", this::actionMsfraggerUpdate);
    JButton btnDownload = UiUtils.createButton("Download", this::actionMsfraggerDownload);

    btnUpdate.setToolTipText(SwingUtils.makeHtml("Open MSFragger upgrader tool in browser.\n" +
        "In order to update you <b>must</b> download an\n" +
        "original copy from the <b>download</b> website once."));
    p.add(btnUpdate, ccL());
    p.add(btnDownload, ccL().wrap());
    epFraggerVer = SwingUtils.createClickableHtml("MSFragger version: N/A");
    p.add(Fragpipe.rename(epFraggerVer, "msfragger.version-info", PREFIX_CONFIG, true), ccL().spanX().growX().wrap());
    p.add(SwingUtils.createClickableHtml(createFraggerCitationBody()), ccL().spanX().growX().wrap());
    return p;
  }

  @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
  public void onMsfraggerUpdateAvailable(MessageMsfraggerUpdateAvailable m) {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("There is a newer version of MSFragger available [%s].<br>\n", m.newVersion));

    JEditorPane ep = SwingUtils.createClickableHtml(sb.toString(), BalloonTips.BG_COLOR);
    JPanel content = new JPanel(new BorderLayout());
    content.setBackground(ep.getBackground());

    JPanel pBtns = new JPanel();
    pBtns.setBackground(ep.getBackground());
    pBtns.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));

    if (!StringUtils.isNullOrWhitespace(m.manualDownloadUrl)) {
      JButton btnManualUpdate = new JButton("Download update");
      btnManualUpdate.addActionListener(e -> {
        try {
          SwingUtils.openBrowserOrThrow(new URI(m.manualDownloadUrl));
        } catch (URISyntaxException ex) {
          throw new IllegalStateException("Incorrect url/uri", ex);
        }
      });
      pBtns.add(btnManualUpdate);
    }

    JButton btnClose = new JButton("Close");
    btnClose.addActionListener(e -> {
      Bus.post(new MessageBalloon(TIP_MSFRAGGER_BIN));
    });

    content.add(ep, BorderLayout.CENTER);
    pBtns.add(btnClose);
    content.add(pBtns, BorderLayout.SOUTH);

    Bus.post(new MessageBalloon(TIP_MSFRAGGER_BIN, uiTextBinFragger, content));
  }

  private CC ccL() {
    return new CC().alignX("left");
  }

  private CC ccR() {
    return new CC().alignX("right");
  }

  private JPanel newMigPanel() {
    return new JPanel(new MigLayout(new LC().fillX()));
  }

  private void actionMsfraggerUpdate(ActionEvent evt) {
    try {
      String url = MsfraggerProps.getProperties()
          .getProperty(MsfraggerProps.PROP_UPDATESERVER_WEBSITE_URL);
      Desktop.getDesktop().browse(URI.create(url));
    } catch (IOException ex) {
      throw new IllegalStateException("Could not open MSFragger update link in browser.", ex);
    }
  }

  private void actionMsfraggerDownload(ActionEvent e) {
    try {
      final String downloadUrl = MsfraggerProps.getProperties().getProperty(MsfraggerProps.PROP_DOWNLOAD_URL, "");
      Desktop.getDesktop().browse(URI.create(downloadUrl));
    } catch (IOException ex) {
      throw new IllegalStateException("Could not open MSFragger download link in browser.", ex);
    }
  }

  private JFileChooser createBinMsfraggerFilechooser() {
    JFileChooser fc = FileChooserUtils.create("Select MSFragger jar", "Select",
        false, FcMode.FILES_ONLY, true,
        new FileNameExtensionFilter("JAR files", "jar"));
    FileChooserUtils.setPath(fc, Stream.of(
        uiTextBinFragger.getNonGhostText(),
        ThisAppProps.load(ThisAppProps.PROP_BINARIES_IN),
        JarUtils.getCurrentJarPath()));
    return fc;
  }

  @Subscribe
  public void onMsfraggerNewBin(MessageMsfraggerNewBin m) {
    if (StringUtils.isBlank(m.binPath))
      return;
    Version v;
    try {
      Msfragger.validateJar(m.binPath);
      v = Msfragger.version(Paths.get(m.binPath));
      Bus.postSticky(new NoteConfigMsfragger(m.binPath, v.version, true, !v.isVersionParsed));
    } catch (ValidationException e) {
      Bus.postSticky(new NoteConfigMsfragger(m.binPath, "N/A", false, false));
    }
  }

  @Subscribe
  public void onNoteConfigMsfragger(NoteConfigMsfragger m) {
    log.debug("Got {}", m);
    uiTextBinFragger.setText(m.jarPath);
    if (!m.isValid) {
      Bus.post(new MessageBalloon(TIP_MSFRAGGER_BIN, uiTextBinFragger, "Not a valid MSFragger jar path"));
    } else if (m.isTooOld) {
      String s = "This MSFragger version is not supported any more, download a newer one.";
      SwingUtils.setJEditorPaneContent(epFraggerVer, s);
      Bus.post(new MessageBalloon(TIP_MSFRAGGER_BIN, uiTextBinFragger, s));
    } else {
      SwingUtils.setJEditorPaneContent(epFraggerVer, "MSFragger version: " + m.version);
    }
    if (m.isValid || m.isTooOld) {
      Msfragger.checkUpdates(m);
    }
  }

  @Subscribe
  public void onMessageUiStateLoaded(MessageUiStateLoaded m) {
    log.debug("Got MessageUiStateLoaded");
    String binFragger = uiTextBinFragger.getNonGhostText();
    if (!StringUtils.isBlank(binFragger)) {
      Bus.post(new MessageMsfraggerNewBin(binFragger));
    }
  }

  public static String createFraggerCitationHtml(Font font) {
    return SwingUtils.wrapInStyledHtml(createFraggerCitationBody(), font);
  }

  private static String createFraggerCitationBody() {
    final Properties p = ThisAppProps.getRemotePropertiesWithLocalDefaults();
    final String linkMsfragger = p.getProperty(MsfraggerProps.PROP_FRAGGER_SITE_URL, "https://nesvilab.github.io/MSFragger/");
    final String linkFragpipe = p.getProperty(ThisAppProps.PROP_FRAGPIPE_SITE_URL, "https://github.com/Nesvilab/FragPipe");
    final String doi= p.getProperty(ThisAppProps.PROP_MANUSCRIPT_DOI, "10.1038/nmeth.4256");
    final String linkManuscript= p.getProperty(ThisAppProps.PROP_MANUSCRIPT_URL, "http://www.nature.com/nmeth/journal/v14/n5/full/nmeth.4256.html");
    final StringBuilder sb = new StringBuilder();

    sb.append("<p style=\"margin-top: 0\">");
    sb.append("<b>Please cite: </b>");
    sb.append(
        "<a href=\"").append(linkManuscript).append("\">MSFragger: ultrafast and comprehensive peptide identification in mass spectrometry–based proteomics</a>");
    sb.append("<br/>");
    sb.append("<b>DOI: </b>").append(doi);
    sb.append("</p>");

    sb.append("<p style=\"margin-top: 10\">");
    sb.append("More info and docs: <a href=\"").append(linkMsfragger).append("\">MSFragger website</a>")
        .append(", <a href=\"").append(linkFragpipe).append("\">FragPipe GitHub page</a>");
    return sb.toString();
  }
}
