package picard.util.help;

import org.broadinstitute.barclay.help.GSONWorkUnit;

/**
 * Class representing a GSONWorkUnit for Picard work units.
 */
public class PicardGSONWorkUnit extends GSONWorkUnit {

    private String walkerType;

    public void setWalkerType(final String walkerType){
        this.walkerType = walkerType;
    }
}
