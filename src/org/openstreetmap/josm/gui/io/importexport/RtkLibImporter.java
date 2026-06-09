// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.io.importexport;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import org.openstreetmap.josm.actions.ExtensionFileFilter;
import org.openstreetmap.josm.gui.io.importexport.GpxImporter.GpxImporterData;
import org.openstreetmap.josm.io.rtklib.RtkLibPosReader;

/**
 * File importer allowing to import RTKLib files (*.pos files).
 * @since 15247
 */
public class RtkLibImporter extends GpxLikeImporter<RtkLibPosReader> {

    /**
     * The RtkLib file filter (*.pos files).
     */
    public static final ExtensionFileFilter FILE_FILTER = ExtensionFileFilter.newFilterWithArchiveExtensions(
            "pos", "pos", tr("RTKLib Positioning Solution Files"), false);

    /**
     * Constructs a new {@code RtkLibImporter}.
     */
    public RtkLibImporter() {
        super(FILE_FILTER, RtkLibPosReader.class);
    }

    /**
     * Replies the new GPX and marker layers corresponding to the specified RTKLib file.
     * @param is input stream to RTKLib data
     * @param associatedFile RTKLib file
     * @param gpxLayerName The GPX layer name
     * @return the new GPX and marker layers corresponding to the specified RTKLib file
     * @throws IOException if an I/O error occurs
     */
    public static GpxImporterData loadLayers(InputStream is, final File associatedFile,
                                             final String gpxLayerName) throws IOException {
        final RtkLibPosReader r = buildAndParse(is, RtkLibPosReader.class);
        final boolean parsedProperly = r.getNumberOfCoordinates() > 0;
        r.getGpxData().storageFile = associatedFile;
        return GpxImporter.loadLayers(r.getGpxData(), parsedProperly, gpxLayerName);
    }
}
