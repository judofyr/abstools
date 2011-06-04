package eu.hatsproject.absplugin.costabslink;

import java.util.ArrayList;

/**
 * This class is used by Costabs plugin in the case it is installed.
 * Costabs check this class to take info from the ABS editor.
 * If costabs plugin is not installed, the ABS editor plugin can work
 * without dependencies in costabs plugin.
 */
public class CostabsLink {

	/**
	 * ABS editor disable some markers that need to be activated in 
	 * eu.hatsproject.absplugin.editor.ABSSourceViewerConfiguration.
	 * (getTextHover and getAnnotationHover)
	 * The costabs upper bound marker is taken from this String.
	 */
	public static String MARKER_UB = "CostabsPlugin.costabs.marker";
	
	/**
	 * Costabs uses the Outline view from ABS editor to take the methods
	 * and functions selected. This methods and funcionts are stored in this
	 * arrays with the line number in the source code. That way, we can put 
	 * a marker next to the method/function.
	 */
	public static ArrayList<String> SELECTED_ITEMS = new ArrayList<String>();
	
	public static ArrayList<Integer> LINE_ITEMS = new ArrayList<Integer>();
	
}
