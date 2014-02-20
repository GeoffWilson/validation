package co.piglet.validation;

import org.bukkit.Location;

import java.io.Serializable;

public class FireworkLocation implements Serializable {

    public int x;
    public int y;
    public int z;

    public FireworkLocation(Location l) {

        x = l.getBlockX();
        y = l.getBlockY();
        z = l.getBlockZ();
    }

    @Override
    public boolean equals(Object other) {

        if (other instanceof FireworkLocation) {
            FireworkLocation location = (FireworkLocation)other;
            return location.x == this.x && location.y == this.y && location.z == this.z;
        }

        return false;
    }
}
