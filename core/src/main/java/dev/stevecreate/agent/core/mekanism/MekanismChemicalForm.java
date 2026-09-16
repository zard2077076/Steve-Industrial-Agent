package dev.stevecreate.agent.core.mekanism;

import dev.stevecreate.agent.core.resource.GenericResourceType;
import java.util.Objects;

/**
 * The four chemical forms Mekanism distinguishes, all of which map to one generic type.
 *
 * <p>{@link GenericResourceType#CHEMICAL} has existed since G-01 but nothing has ever produced
 * or consumed it: it was reserved for this mod and left empty. The generic layer deliberately
 * keeps one category, because a graph edge only needs to know that a chemical flows. Mekanism
 * itself does not: a gas pipe and a slurry pipe move different things, and a machine that
 * accepts an infusion does not accept a pigment. Losing that distinction at the contract layer
 * would let a plan connect two ports the runtime will refuse.</p>
 *
 * <p>Declaration only. Nothing here has been observed against a running Mekanism.</p>
 */
public enum MekanismChemicalForm {
    GAS("gas"),
    INFUSION("infusion"),
    PIGMENT("pigment"),
    SLURRY("slurry");

    private final String serializedName;

    MekanismChemicalForm(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    /** Every form is the same generic category; the distinction lives only in this enum. */
    public GenericResourceType genericResourceType() {
        return GenericResourceType.CHEMICAL;
    }

    public static MekanismChemicalForm fromSerializedName(String serializedName) {
        Objects.requireNonNull(serializedName, "serializedName");
        return switch (serializedName) {
            case "gas" -> GAS;
            case "infusion" -> INFUSION;
            case "pigment" -> PIGMENT;
            case "slurry" -> SLURRY;
            default -> throw new IllegalArgumentException(
                    "Unknown Mekanism chemical form: " + serializedName);
        };
    }
}
