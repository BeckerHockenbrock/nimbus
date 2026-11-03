package dev.nimbus.gui;

import dev.nimbus.client.Location;
import java.awt.BorderLayout;
import java.util.List;
import javax.swing.*;

public final class SearchPanel extends JPanel {
    private final DefaultListModel<Location> model = new DefaultListModel<>();
    private final JList<Location> list = new JList<>(model);
    public SearchPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Matches"));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        add(new JScrollPane(list), BorderLayout.CENTER);
    }
    public JList<Location> locationList() { return list; }
    public void setLocations(List<Location> locations) { model.clear(); locations.forEach(model::addElement); }
}
