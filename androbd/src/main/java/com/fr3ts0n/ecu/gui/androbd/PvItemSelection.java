package com.fr3ts0n.ecu.gui.androbd;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Select PVs without assuming that native map keys are strings (startup uses Integer 0). */
final class PvItemSelection
{
    private PvItemSelection() { }

    /** null selection means no saved preference; an empty selection means explicitly none. */
    static <V> Set<V> select(Map<?, V> items, Set<?> selectedKeys)
    {
        Set<V> result = new HashSet<>();
        if (selectedKeys == null)
        {
            result.addAll(items.values());
        }
        else
        {
            for (Object key : selectedKeys)
            {
                V value = items.get(key);
                if (value != null) result.add(value);
            }
        }
        result.remove(null);
        return result;
    }
}
