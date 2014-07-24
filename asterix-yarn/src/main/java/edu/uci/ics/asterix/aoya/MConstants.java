package edu.uci.ics.asterix.aoya;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/**
 * Constants used in both Client and Application Master
 */
@InterfaceAudience.Public
@InterfaceStability.Unstable
public class MConstants {
    /**
     * Environment key name pointing to the the app master jar location
     */
    public static final String APPLICATIONMASTERJARLOCATION = "APPLICATIONMASTERJARLOCATION";

    /**
     * Environment key name denoting the file timestamp for the the app master jar.
     * Used to validate the local resource.
     */
    public static final String APPLICATIONMASTERJARTIMESTAMP = "APPLICATIONMASTERJARTIMESTAMP";

    /**
     * Environment key name denoting the file content length for the app master jar.
     * Used to validate the local resource.
     */
    public static final String APPLICATIONMASTERJARLEN = "APPLICATIONMASTERJARLEN";
    /**
     * Environment key name pointing to the Asterix distributable tar
     */
    public static final String TARLOCATION = "TARLOCATION";

    /**
     * Environment key name denoting the file timestamp for the Asterix tar.
     * Used to validate the local resource.
     */
    public static final String TARTIMESTAMP = "TARTIMESTAMP";

    /**
     * Environment key name denoting the file content length for the Asterix tar.
     * Used to validate the local resource.
     */
    public static final String TARLEN = "TARLEN";

    /**
     * Environment key name pointing to the Asterix distributable external libraries
     */
    public static final String EXTLOCATION = "EXTLOCATION";

    /**
     * Environment key name denoting the file timestamp for the Asterix external libraries.
     * Used to validate the local resource.
     */
    public static final String EXTTIMESTAMP = "EXTTIMESTAMP";

    /**
     * Environment key name denoting the file content length for the Asterix external libraries.
     * Used to validate the local resource.
     */
    public static final String EXTLEN = "EXTLEN";

    /**
     * Environment key name pointing to the Asterix cluster configuration file
     */
    public static final String CONFLOCATION = "CONFLOCATION";

    /**
     * Environment key name denoting the file timestamp for the Asterix config.
     * Used to validate the local resource.
     */

    public static final String CONFTIMESTAMP = "CONFTIMESTAMP";

    /**
     * Environment key name denoting the file content length for the Asterix config.
     * Used to validate the local resource.
     */

    public static final String CONFLEN = "CONFLEN";

    /**
     * Environment key name pointing to the Asterix parameters file
     */

    public static final String PARAMLOCATION = "PARAMLOCATION";

    /**
     * Environment key name denoting the file timestamp for the Asterix parameters.
     * Used to validate the local resource.
     */

    public static final String PARAMTIMESTAMP = "PARAMTIMESTAMP";

    /**
     * Environment key name denoting the file content length for the Asterix parameters.
     * Used to validate the local resource.
     */

    public static final String PARAMLEN = "PARAMLEN";

    public static final String PATHSUFFIX = "PATHSUFFIX";
}