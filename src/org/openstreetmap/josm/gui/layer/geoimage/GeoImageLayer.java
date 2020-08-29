// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.layer.geoimage;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.AutoScaleAction;
import org.openstreetmap.josm.actions.RenameLayerAction;
import org.openstreetmap.josm.actions.mapmode.SelectLassoAction;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.actions.mapmode.SelectAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Data;
import org.openstreetmap.josm.data.ImageData;
import org.openstreetmap.josm.data.ImageData.ImageDataUpdateListener;
import org.openstreetmap.josm.data.gpx.GpxData;
import org.openstreetmap.josm.data.gpx.WayPoint;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapFrame.MapModeChangeListener;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.NavigatableComponent;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.dialogs.LayerListDialog;
import org.openstreetmap.josm.gui.dialogs.LayerListPopup;
import org.openstreetmap.josm.gui.io.importexport.JpgImporter;
import org.openstreetmap.josm.gui.layer.AbstractModifiableLayer;
import org.openstreetmap.josm.gui.layer.GpxLayer;
import org.openstreetmap.josm.gui.layer.JumpToMarkerActions.JumpToMarkerLayer;
import org.openstreetmap.josm.gui.layer.JumpToMarkerActions.JumpToNextMarker;
import org.openstreetmap.josm.gui.layer.JumpToMarkerActions.JumpToPreviousMarker;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

/**
 * Layer displaying geottaged pictures.
 */
public class GeoImageLayer extends AbstractModifiableLayer implements
        JumpToMarkerLayer, NavigatableComponent.ZoomChangeListener, ImageDataUpdateListener {

    private static final List<Action> menuAdditions = new LinkedList<>();

    private static volatile List<MapMode> supportedMapModes;

    private final ImageData data;
    GpxLayer gpxLayer;
    GpxLayer gpxFauxLayer;

    private final Icon icon = ImageProvider.get("dialogs/geoimage/photo-marker");
    private final Icon selectedIcon = ImageProvider.get("dialogs/geoimage/photo-marker-selected");

    boolean useThumbs;
    private final ExecutorService thumbsLoaderExecutor =
            Executors.newSingleThreadExecutor(Utils.newThreadFactory("thumbnail-loader-%d", Thread.MIN_PRIORITY));
    private ThumbsLoader thumbsloader;
    private boolean thumbsLoaderRunning;
    volatile boolean thumbsLoaded;
    private BufferedImage offscreenBuffer;
    private boolean updateOffscreenBuffer = true;

    private MouseAdapter mouseAdapter;
    private MouseMotionAdapter mouseMotionAdapter;
    private MapModeChangeListener mapModeListener;
    private ActiveLayerChangeListener activeLayerChangeListener;

    /** Mouse position where the last image was selected. */
    private Point lastSelPos;
    /** The mouse point */
    private Point startPoint;

    /**
     * Image cycle mode flag.
     * It is possible that a mouse button release triggers multiple mouseReleased() events.
     * To prevent the cycling in such a case we wait for the next mouse button press event
     * before it is cycled to the next image.
     */
    private boolean cycleModeArmed;

    /**
     * Constructs a new {@code GeoImageLayer}.
     * @param data The list of images to display
     * @param gpxLayer The associated GPX layer
     */
    public GeoImageLayer(final List<ImageEntry> data, GpxLayer gpxLayer) {
        this(data, gpxLayer, null, false);
    }

    /**
     * Constructs a new {@code GeoImageLayer}.
     * @param data The list of images to display
     * @param gpxLayer The associated GPX layer
     * @param name Layer name
     * @since 6392
     */
    public GeoImageLayer(final List<ImageEntry> data, GpxLayer gpxLayer, final String name) {
        this(data, gpxLayer, name, false);
    }

    /**
     * Constructs a new {@code GeoImageLayer}.
     * @param data The list of images to display
     * @param gpxLayer The associated GPX layer
     * @param useThumbs Thumbnail display flag
     * @since 6392
     */
    public GeoImageLayer(final List<ImageEntry> data, GpxLayer gpxLayer, boolean useThumbs) {
        this(data, gpxLayer, null, useThumbs);
    }

    /**
     * Constructs a new {@code GeoImageLayer}.
     * @param data The list of images to display
     * @param gpxLayer The associated GPX layer
     * @param name Layer name
     * @param useThumbs Thumbnail display flag
     * @since 6392
     */
    public GeoImageLayer(final List<ImageEntry> data, GpxLayer gpxLayer, final String name, boolean useThumbs) {
        super(name != null ? name : tr("Geotagged Images"));
        this.data = new ImageData(data);
        this.gpxLayer = gpxLayer;
        this.useThumbs = useThumbs;
        this.data.addImageDataUpdateListener(this);
    }

    private final class ImageMouseListener extends MouseAdapter {
        private boolean isMapModeOk() {
            MapMode mapMode = MainApplication.getMap().mapMode;
            return mapMode == null || isSupportedMapMode(mapMode);
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (e.getButton() != MouseEvent.BUTTON1)
                return;
            if (isVisible() && isMapModeOk()) {
                cycleModeArmed = true;
                invalidate();
                startPoint = e.getPoint();
            }
        }

        @Override
        public void mouseReleased(MouseEvent ev) {
            if (ev.getButton() != MouseEvent.BUTTON1)
                return;
            if (!isVisible() || !isMapModeOk())
                return;
            if (!cycleModeArmed) {
                return;
            }

            Rectangle hitBoxClick = new Rectangle((int) startPoint.getX() - 10, (int) startPoint.getY() - 10, 15, 15);
            if (!hitBoxClick.contains(ev.getPoint())) {
                return;
            }

            Point mousePos = ev.getPoint();
            boolean cycle = cycleModeArmed && lastSelPos != null && lastSelPos.equals(mousePos);
            final boolean isShift = (ev.getModifiersEx() & MouseEvent.SHIFT_DOWN_MASK) == MouseEvent.SHIFT_DOWN_MASK;
            final boolean isCtrl = (ev.getModifiersEx() & MouseEvent.CTRL_DOWN_MASK) == MouseEvent.CTRL_DOWN_MASK;
            int idx = getPhotoIdxUnderMouse(ev, cycle);
            if (idx >= 0) {
                lastSelPos = mousePos;
                cycleModeArmed = false;
                ImageEntry img = data.getImages().get(idx);
                if (isShift) {
                    if (isCtrl && !data.getSelectedImages().isEmpty()) {
                        int idx2 = data.getImages().indexOf(data.getSelectedImages().get(data.getSelectedImages().size() - 1));
                        int startIndex = Math.min(idx, idx2);
                        int endIndex = Math.max(idx, idx2);
                        for (int i = startIndex; i <= endIndex; i++) {
                            data.addImageToSelection(data.getImages().get(i));
                        }
                    } else {
                        if (data.isImageSelected(img)) {
                            data.removeImageToSelection(img);
                        } else {
                            data.addImageToSelection(img);
                        }
                    }
                } else {
                    data.setSelectedImage(img);
                }
            }
        }
    }

    /**
     * Loads a set of images, while displaying a dialog that indicates what the plugin is currently doing.
     * In facts, this object is instantiated with a list of files. These files may be JPEG files or
     * directories. In case of directories, they are scanned to find all the images they contain.
     * Then all the images that have be found are loaded as ImageEntry instances.
     */
    static final class Loader extends PleaseWaitRunnable {

        private boolean canceled;
        private GeoImageLayer layer;
        private final Collection<File> selection;
        private final Set<String> loadedDirectories = new HashSet<>();
        private final Set<String> errorMessages;
        private final GpxLayer gpxLayer;

        Loader(Collection<File> selection, GpxLayer gpxLayer) {
            super(tr("Extracting GPS locations from EXIF"));
            this.selection = selection;
            this.gpxLayer = gpxLayer;
            errorMessages = new LinkedHashSet<>();
        }

        private void rememberError(String message) {
            this.errorMessages.add(message);
        }

        @Override
        protected void realRun() throws IOException {

            progressMonitor.subTask(tr("Starting directory scan"));
            Collection<File> files = new ArrayList<>();
            try {
                addRecursiveFiles(files, selection);
            } catch (IllegalStateException e) {
                Logging.debug(e);
                rememberError(e.getMessage());
            }

            if (canceled)
                return;
            progressMonitor.subTask(tr("Read photos..."));
            progressMonitor.setTicksCount(files.size());

            // read the image files
            List<ImageEntry> entries = new ArrayList<>(files.size());

            for (File f : files) {

                if (canceled) {
                    break;
                }

                progressMonitor.subTask(tr("Reading {0}...", f.getName()));
                progressMonitor.worked(1);

                ImageEntry e = new ImageEntry(f);
                e.extractExif();
                entries.add(e);
            }
            layer = new GeoImageLayer(entries, gpxLayer);
            files.clear();
        }

        private void addRecursiveFiles(Collection<File> files, Collection<File> sel) {
            boolean nullFile = false;

            for (File f : sel) {

                if (canceled) {
                    break;
                }

                if (f == null) {
                    nullFile = true;

                } else if (f.isDirectory()) {
                    String canonical = null;
                    try {
                        canonical = f.getCanonicalPath();
                    } catch (IOException e) {
                        Logging.error(e);
                        rememberError(tr("Unable to get canonical path for directory {0}\n",
                                f.getAbsolutePath()));
                    }

                    if (canonical == null || loadedDirectories.contains(canonical)) {
                        continue;
                    } else {
                        loadedDirectories.add(canonical);
                    }

                    File[] children = f.listFiles(JpgImporter.FILE_FILTER_WITH_FOLDERS);
                    if (children != null) {
                        progressMonitor.subTask(tr("Scanning directory {0}", f.getPath()));
                        addRecursiveFiles(files, Arrays.asList(children));
                    } else {
                        rememberError(tr("Error while getting files from directory {0}\n", f.getPath()));
                    }

                } else {
                    files.add(f);
                }
            }

            if (nullFile) {
                throw new IllegalStateException(tr("One of the selected files was null"));
            }
        }

        private String formatErrorMessages() {
            StringBuilder sb = new StringBuilder();
            sb.append("<html>");
            if (errorMessages.size() == 1) {
                sb.append(Utils.escapeReservedCharactersHTML(errorMessages.iterator().next()));
            } else {
                sb.append(Utils.joinAsHtmlUnorderedList(errorMessages));
            }
            sb.append("</html>");
            return sb.toString();
        }

        @Override protected void finish() {
            if (!errorMessages.isEmpty()) {
                JOptionPane.showMessageDialog(
                        MainApplication.getMainFrame(),
                        formatErrorMessages(),
                        tr("Error"),
                        JOptionPane.ERROR_MESSAGE
                        );
            }
            if (layer != null) {
                MainApplication.getLayerManager().addLayer(layer);

                if (!canceled && !layer.getImageData().getImages().isEmpty()) {
                    boolean noGeotagFound = true;
                    for (ImageEntry e : layer.getImageData().getImages()) {
                        if (e.getPos() != null) {
                            noGeotagFound = false;
                        }
                    }
                    if (noGeotagFound) {
                        new CorrelateGpxWithImages(layer).actionPerformed(null);
                    }
                }
            }
        }

        @Override protected void cancel() {
            canceled = true;
        }
    }

    /**
     * Create a GeoImageLayer asynchronously
     * @param files the list of image files to display
     * @param gpxLayer the gpx layer
     */
    public static void create(Collection<File> files, GpxLayer gpxLayer) {
        MainApplication.worker.execute(new Loader(files, gpxLayer));
    }

    @Override
    public Icon getIcon() {
        return ImageProvider.get("dialogs/geoimage", ImageProvider.ImageSizes.LAYER);
    }

    /**
     * Register actions on the layer
     * @param addition the action to be added
     */
    public static void registerMenuAddition(Action addition) {
        menuAdditions.add(addition);
    }

    @Override
    public Action[] getMenuEntries() {

        List<Action> entries = new ArrayList<>();
        entries.add(LayerListDialog.getInstance().createShowHideLayerAction());
        entries.add(LayerListDialog.getInstance().createDeleteLayerAction());
        entries.add(MainApplication.getMenu().autoScaleActions.get(AutoScaleAction.AutoScaleMode.LAYER));
        entries.add(LayerListDialog.getInstance().createMergeLayerAction(this));
        entries.add(new RenameLayerAction(null, this));
        entries.add(SeparatorLayerAction.INSTANCE);
        entries.add(new CorrelateGpxWithImages(this));
        entries.add(new ShowThumbnailAction(this));
        if (!menuAdditions.isEmpty()) {
            entries.add(SeparatorLayerAction.INSTANCE);
            entries.addAll(menuAdditions);
        }
        entries.add(SeparatorLayerAction.INSTANCE);
        entries.add(new JumpToNextMarker(this));
        entries.add(new JumpToPreviousMarker(this));
        entries.add(SeparatorLayerAction.INSTANCE);
        entries.add(new LayerListPopup.InfoAction(this));

        return entries.toArray(new Action[0]);

    }

    /**
     * Prepare the string that is displayed if layer information is requested.
     * @return String with layer information
     */
    private String infoText() {
        int tagged = 0;
        int newdata = 0;
        int n = data.getImages().size();
        for (ImageEntry e : data.getImages()) {
            if (e.getPos() != null) {
                tagged++;
            }
            if (e.hasNewGpsData()) {
                newdata++;
            }
        }
        return "<html>"
                + trn("{0} image loaded.", "{0} images loaded.", n, n)
                + ' ' + trn("{0} was found to be GPS tagged.", "{0} were found to be GPS tagged.", tagged, tagged)
                + (newdata > 0 ? "<br>" + trn("{0} has updated GPS data.", "{0} have updated GPS data.", newdata, newdata) : "")
                + "</html>";
    }

    @Override public Object getInfoComponent() {
        return infoText();
    }

    @Override
    public String getToolTipText() {
        return infoText();
    }

    /**
     * Determines if data managed by this layer has been modified.  That is
     * the case if one image has modified GPS data.
     * @return {@code true} if data has been modified; {@code false}, otherwise
     */
    @Override
    public boolean isModified() {
        return this.data.isModified();
    }

    @Override
    public boolean isMergable(Layer other) {
        return other instanceof GeoImageLayer;
    }

    @Override
    public void mergeFrom(Layer from) {
        if (!(from instanceof GeoImageLayer))
            throw new IllegalArgumentException("not a GeoImageLayer: " + from);
        GeoImageLayer l = (GeoImageLayer) from;

        // Stop to load thumbnails on both layers.  Thumbnail loading will continue the next time
        // the layer is painted.
        stopLoadThumbs();
        l.stopLoadThumbs();

        this.data.mergeFrom(l.getImageData());

        setName(l.getName());
        thumbsLoaded &= l.thumbsLoaded;
    }

    private static Dimension scaledDimension(Image thumb) {
        final double d = MainApplication.getMap().mapView.getDist100Pixel();
        final double size = 10 /*meter*/;     /* size of the photo on the map */
        double s = size * 100 /*px*/ / d;

        final double sMin = ThumbsLoader.minSize;
        final double sMax = ThumbsLoader.maxSize;

        if (s < sMin) {
            s = sMin;
        }
        if (s > sMax) {
            s = sMax;
        }
        final double f = s / sMax;  /* scale factor */

        if (thumb == null)
            return null;

        return new Dimension(
                (int) Math.round(f * thumb.getWidth(null)),
                (int) Math.round(f * thumb.getHeight(null)));
    }

    /**
     * Paint one image.
     * @param e Image to be painted
     * @param mv Map view
     * @param clip Bounding rectangle of the current clipping area
     * @param tempG Temporary offscreen buffer
     */
    private void paintImage(ImageEntry e, MapView mv, Rectangle clip, Graphics2D tempG) {
        if (e.getPos() == null) {
            return;
        }
        Point p = mv.getPoint(e.getPos());
        if (e.hasThumbnail()) {
            Dimension d = scaledDimension(e.getThumbnail());
            if (d != null) {
                Rectangle target = new Rectangle(p.x - d.width / 2, p.y - d.height / 2, d.width, d.height);
                if (clip.intersects(target)) {
                    tempG.drawImage(e.getThumbnail(), target.x, target.y, target.width, target.height, null);
                }
            }
        } else { // thumbnail not loaded yet
            icon.paintIcon(mv, tempG,
                p.x - icon.getIconWidth() / 2,
                p.y - icon.getIconHeight() / 2);
        }
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bounds) {
        int width = mv.getWidth();
        int height = mv.getHeight();
        Rectangle clip = g.getClipBounds();
        if (useThumbs) {
            if (!thumbsLoaded) {
                startLoadThumbs();
            }

            if (null == offscreenBuffer || offscreenBuffer.getWidth() != width  // reuse the old buffer if possible
                    || offscreenBuffer.getHeight() != height) {
                offscreenBuffer = new BufferedImage(width, height,
                        BufferedImage.TYPE_INT_ARGB);
                updateOffscreenBuffer = true;
            }

            if (updateOffscreenBuffer) {
                Graphics2D tempG = offscreenBuffer.createGraphics();
                tempG.setColor(new Color(0, 0, 0, 0));
                Composite saveComp = tempG.getComposite();
                tempG.setComposite(AlphaComposite.Clear);   // remove the old images
                tempG.fillRect(0, 0, width, height);
                tempG.setComposite(saveComp);

                for (ImageEntry e : data.getImages()) {
                    paintImage(e, mv, clip, tempG);
                }
                for (ImageEntry img: this.data.getSelectedImages()) {
                    // Make sure the selected image is on top in case multiple images overlap.
                    paintImage(img, mv, clip, tempG);
                }
                updateOffscreenBuffer = false;
            }
            g.drawImage(offscreenBuffer, 0, 0, null);
        } else {
            for (ImageEntry e : data.getImages()) {
                if (e.getPos() == null) {
                    continue;
                }
                Point p = mv.getPoint(e.getPos());
                icon.paintIcon(mv, g,
                        p.x - icon.getIconWidth() / 2,
                        p.y - icon.getIconHeight() / 2);
            }
        }

        for (ImageEntry e: data.getSelectedImages()) {
            if (e != null && e.getPos() != null) {
                Point p = mv.getPoint(e.getPos());

                int imgWidth;
                int imgHeight;
                if (useThumbs && e.hasThumbnail()) {
                    Dimension d = scaledDimension(e.getThumbnail());
                    if (d != null) {
                        imgWidth = d.width;
                        imgHeight = d.height;
                    } else {
                        imgWidth = -1;
                        imgHeight = -1;
                    }
                } else {
                    imgWidth = selectedIcon.getIconWidth();
                    imgHeight = selectedIcon.getIconHeight();
                }

                if (e.getExifImgDir() != null) {
                    // Multiplier must be larger than sqrt(2)/2=0.71.
                    double arrowlength = Math.max(25, Math.max(imgWidth, imgHeight) * 0.85);
                    double arrowwidth = arrowlength / 1.4;

                    double dir = e.getExifImgDir();
                    // Rotate 90 degrees CCW
                    double headdir = (dir < 90) ? dir + 270 : dir - 90;
                    double leftdir = (headdir < 90) ? headdir + 270 : headdir - 90;
                    double rightdir = (headdir > 270) ? headdir - 270 : headdir + 90;

                    double ptx = p.x + Math.cos(Utils.toRadians(headdir)) * arrowlength;
                    double pty = p.y + Math.sin(Utils.toRadians(headdir)) * arrowlength;

                    double ltx = p.x + Math.cos(Utils.toRadians(leftdir)) * arrowwidth/2;
                    double lty = p.y + Math.sin(Utils.toRadians(leftdir)) * arrowwidth/2;

                    double rtx = p.x + Math.cos(Utils.toRadians(rightdir)) * arrowwidth/2;
                    double rty = p.y + Math.sin(Utils.toRadians(rightdir)) * arrowwidth/2;

                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(new Color(255, 255, 255, 192));
                    int[] xar = {(int) ltx, (int) ptx, (int) rtx, (int) ltx};
                    int[] yar = {(int) lty, (int) pty, (int) rty, (int) lty};
                    g.fillPolygon(xar, yar, 4);
                    g.setColor(Color.black);
                    g.setStroke(new BasicStroke(1.2f));
                    g.drawPolyline(xar, yar, 3);
                }

                if (useThumbs && e.hasThumbnail()) {
                    g.setColor(new Color(128, 0, 0, 122));
                    g.fillRect(p.x - imgWidth / 2, p.y - imgHeight / 2, imgWidth, imgHeight);
                } else {
                    selectedIcon.paintIcon(mv, g,
                            p.x - imgWidth / 2,
                            p.y - imgHeight / 2);
                }
            }
        }
    }

    @Override
    public void visitBoundingBox(BoundingXYVisitor v) {
        for (ImageEntry e : data.getImages()) {
            v.visit(e.getPos());
        }
    }

    /**
     * Show current photo on map and in image viewer.
     */
    public void showCurrentPhoto() {
        if (data.getSelectedImage() != null) {
            clearOtherCurrentPhotos();
        }
        updateBufferAndRepaint();
    }

    /**
     * Check if the position of the mouse event is within the rectangle of the photo icon or thumbnail.
     * @param idx the image index
     * @param evt Mouse event
     * @return {@code true} if the photo matches the mouse position, {@code false} otherwise
     */
    private boolean isPhotoIdxUnderMouse(int idx, MouseEvent evt) {
        ImageEntry img = data.getImages().get(idx);
        if (img.getPos() != null) {
            Point imgCenter = MainApplication.getMap().mapView.getPoint(img.getPos());
            Rectangle imgRect;
            if (useThumbs && img.hasThumbnail()) {
                Dimension imgDim = scaledDimension(img.getThumbnail());
                if (imgDim != null) {
                    imgRect = new Rectangle(imgCenter.x - imgDim.width / 2,
                                            imgCenter.y - imgDim.height / 2,
                                            imgDim.width, imgDim.height);
                } else {
                    imgRect = null;
                }
            } else {
                imgRect = new Rectangle(imgCenter.x - icon.getIconWidth() / 2,
                                        imgCenter.y - icon.getIconHeight() / 2,
                                        icon.getIconWidth(), icon.getIconHeight());
            }
            if (imgRect != null && imgRect.contains(evt.getPoint())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns index of the image that matches the position of the mouse event.
     * @param evt    Mouse event
     * @param cycle  Set to {@code true} to cycle through the photos at the
     *               current mouse position if multiple icons or thumbnails overlap.
     *               If set to {@code false} the topmost photo will be used.
     * @return       Image index at mouse position, range 0 .. size-1,
     *               or {@code -1} if there is no image at the mouse position
     */
    private int getPhotoIdxUnderMouse(MouseEvent evt, boolean cycle) {
        ImageEntry selectedImage = data.getSelectedImage();
        int selectedIndex = data.getImages().indexOf(selectedImage);

        if (cycle && selectedImage != null) {
            // Cycle loop is forward as that is the natural order.
            // Loop 1: One after current photo up to last one.
            for (int idx = selectedIndex + 1; idx < data.getImages().size(); ++idx) {
                if (isPhotoIdxUnderMouse(idx, evt)) {
                    return idx;
                }
            }
            // Loop 2: First photo up to current one.
            for (int idx = 0; idx <= selectedIndex; ++idx) {
                if (isPhotoIdxUnderMouse(idx, evt)) {
                    return idx;
                }
            }
        } else {
            // Check for current photo first, i.e. keep it selected if it is under the mouse.
            if (selectedImage != null && isPhotoIdxUnderMouse(selectedIndex, evt)) {
                return selectedIndex;
            }
            // Loop from last to first to prefer topmost image.
            for (int idx = data.getImages().size() - 1; idx >= 0; --idx) {
                if (isPhotoIdxUnderMouse(idx, evt)) {
                    return idx;
                }
            }
        }
        return -1;
    }

    /**
     * Returns index of the image that matches the position of the mouse event.
     * The topmost photo is picked if multiple icons or thumbnails overlap.
     * @param evt Mouse event
     * @return Image index at mouse position, range 0 .. size-1,
     *         or {@code -1} if there is no image at the mouse position
     */
    private int getPhotoIdxUnderMouse(MouseEvent evt) {
        return getPhotoIdxUnderMouse(evt, false);
    }

    /**
     * Returns the image that matches the position of the mouse event.
     * The topmost photo is picked of multiple icons or thumbnails overlap.
     * @param evt Mouse event
     * @return Image at mouse position, or {@code null} if there is no image at the mouse position
     * @since 6392
     */
    public ImageEntry getPhotoUnderMouse(MouseEvent evt) {
        int idx = getPhotoIdxUnderMouse(evt);
        if (idx >= 0) {
            return data.getImages().get(idx);
        } else {
            return null;
        }
    }

    /**
     * Clears the currentPhoto of the other GeoImageLayer's. Otherwise there could be multiple selected photos.
     */
    private void clearOtherCurrentPhotos() {
        for (GeoImageLayer layer:
                 MainApplication.getLayerManager().getLayersOfType(GeoImageLayer.class)) {
            if (layer != this) {
                layer.getImageData().clearSelectedImage();
            }
        }
    }

    /**
     * Registers a map mode for which the functionality of this layer should be available.
     * @param mapMode Map mode to be registered
     * @since 6392
     */
    public static void registerSupportedMapMode(MapMode mapMode) {
        if (supportedMapModes == null) {
            supportedMapModes = new ArrayList<>();
        }
        supportedMapModes.add(mapMode);
    }

    /**
     * Determines if the functionality of this layer is available in
     * the specified map mode. {@link SelectAction} and {@link SelectLassoAction} are supported by default,
     * other map modes can be registered.
     * @param mapMode Map mode to be checked
     * @return {@code true} if the map mode is supported,
     *         {@code false} otherwise
     */
    private static boolean isSupportedMapMode(MapMode mapMode) {
        if (mapMode instanceof SelectAction || mapMode instanceof SelectLassoAction) {
            return true;
        }
        return supportedMapModes != null && supportedMapModes.stream().anyMatch(supmmode -> mapMode == supmmode);
    }

    @Override
    public void hookUpMapView() {
        mouseAdapter = new ImageMouseListener();

        mouseMotionAdapter = new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent evt) {
                lastSelPos = null;
            }

            @Override
            public void mouseDragged(MouseEvent evt) {
                lastSelPos = null;
            }
        };

        mapModeListener = (oldMapMode, newMapMode) -> {
            MapView mapView = MainApplication.getMap().mapView;
            if (newMapMode == null || isSupportedMapMode(newMapMode)) {
                mapView.addMouseListener(mouseAdapter);
                mapView.addMouseMotionListener(mouseMotionAdapter);
            } else {
                mapView.removeMouseListener(mouseAdapter);
                mapView.removeMouseMotionListener(mouseMotionAdapter);
            }
        };

        MapFrame.addMapModeChangeListener(mapModeListener);
        mapModeListener.mapModeChange(null, MainApplication.getMap().mapMode);

        activeLayerChangeListener = e -> {
            if (MainApplication.getLayerManager().getActiveLayer() == this) {
                // only in select mode it is possible to click the images
                MainApplication.getMap().selectSelectTool(false);
            }
        };
        MainApplication.getLayerManager().addActiveLayerChangeListener(activeLayerChangeListener);

        MapFrame map = MainApplication.getMap();
        if (map.getToggleDialog(ImageViewerDialog.class) == null) {
            ImageViewerDialog.createInstance();
            map.addToggleDialog(ImageViewerDialog.getInstance());
        }
    }

    @Override
    public synchronized void destroy() {
        super.destroy();
        stopLoadThumbs();
        MapView mapView = MainApplication.getMap().mapView;
        mapView.removeMouseListener(mouseAdapter);
        mapView.removeMouseMotionListener(mouseMotionAdapter);
        MapView.removeZoomChangeListener(this);
        MapFrame.removeMapModeChangeListener(mapModeListener);
        MainApplication.getLayerManager().removeActiveLayerChangeListener(activeLayerChangeListener);
        data.removeImageDataUpdateListener(this);
    }

    @Override
    public LayerPainter attachToMapView(MapViewEvent event) {
        MapView.addZoomChangeListener(this);
        return new CompatibilityModeLayerPainter() {
            @Override
            public void detachFromMapView(MapViewEvent event) {
                MapView.removeZoomChangeListener(GeoImageLayer.this);
            }
        };
    }

    @Override
    public void zoomChanged() {
        updateBufferAndRepaint();
    }

    /**
     * Start to load thumbnails.
     */
    public synchronized void startLoadThumbs() {
        if (useThumbs && !thumbsLoaded && !thumbsLoaderRunning) {
            stopLoadThumbs();
            thumbsloader = new ThumbsLoader(this);
            thumbsLoaderExecutor.submit(thumbsloader);
            thumbsLoaderRunning = true;
        }
    }

    /**
     * Stop to load thumbnails.
     *
     * Can be called at any time to make sure that the
     * thumbnail loader is stopped.
     */
    public synchronized void stopLoadThumbs() {
        if (thumbsloader != null) {
            thumbsloader.stop = true;
        }
        thumbsLoaderRunning = false;
    }

    /**
     * Called to signal that the loading of thumbnails has finished.
     *
     * Usually called from {@link ThumbsLoader} in another thread.
     */
    public void thumbsLoaded() {
        thumbsLoaded = true;
    }

    /**
     * Marks the offscreen buffer to be updated.
     */
    public void updateBufferAndRepaint() {
        updateOffscreenBuffer = true;
        invalidate();
    }

    /**
     * Get list of images in layer.
     * @return List of images in layer
     */
    public List<ImageEntry> getImages() {
        return new ArrayList<>(data.getImages());
    }

    /**
     * Returns the image data store being used by this layer
     * @return imageData
     * @since 14590
     */
    public ImageData getImageData() {
        return data;
    }

    /**
     * Returns the associated GPX layer.
     * @return The associated GPX layer
     */
    public GpxLayer getGpxLayer() {
        return gpxLayer;
    }

    /**
     * Returns a faux GPX layer built from the images or the associated GPX layer.
     * @return A faux GPX layer or the associated GPX layer
     * @since 14802
     */
    public synchronized GpxLayer getFauxGpxLayer() {
        if (gpxLayer != null) return getGpxLayer();
        if (gpxFauxLayer == null) {
            GpxData gpxData = new GpxData();
            List<ImageEntry> imageList = data.getImages();
            for (ImageEntry image : imageList) {
                WayPoint twaypoint = new WayPoint(image.getPos());
                gpxData.addWaypoint(twaypoint);
            }
            gpxFauxLayer = new GpxLayer(gpxData);
        }
        return gpxFauxLayer;
    }

    @Override
    public void jumpToNextMarker() {
        data.selectNextImage();
    }

    @Override
    public void jumpToPreviousMarker() {
        data.selectPreviousImage();
    }

    /**
     * Returns the current thumbnail display status.
     * {@code true}: thumbnails are displayed, {@code false}: an icon is displayed instead of thumbnails.
     * @return Current thumbnail display status
     * @since 6392
     */
    public boolean isUseThumbs() {
        return useThumbs;
    }

    /**
     * Enables or disables the display of thumbnails.  Does not update the display.
     * @param useThumbs New thumbnail display status
     * @since 6392
     */
    public void setUseThumbs(boolean useThumbs) {
        this.useThumbs = useThumbs;
        if (useThumbs && !thumbsLoaded) {
            startLoadThumbs();
        } else if (!useThumbs) {
            stopLoadThumbs();
        }
        invalidate();
    }

    @Override
    public void selectedImageChanged(ImageData data) {
        showCurrentPhoto();
    }

    @Override
    public void imageDataUpdated(ImageData data) {
        updateBufferAndRepaint();
    }

    @Override
    public String getChangesetSourceTag() {
        return "Geotagged Images";
    }

    @Override
    public Data getData() {
        return data;
    }
}
