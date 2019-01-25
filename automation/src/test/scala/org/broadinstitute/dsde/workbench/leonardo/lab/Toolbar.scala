package org.broadinstitute.dsde.workbench.leonardo.lab


trait Toolbar {

  // selects all menus from the header bar
  lazy val menus: String = "[class='p-MenuBar-item']"

  lazy val menuItems: String ="[class='p-Menu-itemLabel']"

  // Run Cell toolbar button
  lazy val runCellButton: String = "[title='Run the selected cells and advance']"

  // Kernel -> Shutdown
  lazy val shutdownKernelSelection: String = "[data-command='kernelmenu:shutdown']"

}

