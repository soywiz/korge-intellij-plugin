package com.soywiz.korge.intellij.editor.tile

import com.intellij.ui.components.*
import com.soywiz.kmem.*
import com.soywiz.korge.intellij.editor.*
import com.soywiz.korge.intellij.ui.*
import com.soywiz.korge.intellij.util.*
import com.soywiz.korim.bitmap.*
import com.soywiz.korim.color.*
import com.soywiz.korio.async.*
import com.soywiz.korio.file.*
import com.soywiz.korio.file.std.*
import com.soywiz.korma.geom.*
import kotlinx.coroutines.*
import java.awt.*
import java.awt.event.*
import javax.swing.*
import kotlin.math.*

fun Styled<out Container>.createTileMapEditor(
		tilemap: TiledMap = runBlocking { localCurrentDirVfs["samples/gfx/sample.tmx"].readTiledMap() },
		history: HistoryManager = HistoryManager(),
		registerHistoryShortcuts: Boolean = true
) {
	//val zoomLevels = listOf(10, 15, 25, 50, 75, 100, 150, 200, 300, 400, 700, 1000, 1500, 2000, 2500, 3000)
	val zoomLevels = listOf(25, 50, 75, 100, 150, 200, 300, 400, 700, 1000, 1500, 2000, 2500, 3000)
	val zoomLevel = ObservableProperty(zoomLevels.indexOf(200)) { it.clamp(0, zoomLevels.size - 1) }
	val unsavedChanges = ObservableProperty(false)
	val updateLayersSignal = Signal<Unit>()
	val updateTilemap = Signal<Unit>()
	fun zoomRatio(): Double = zoomLevels[zoomLevel.value].toDouble() / 100.0
	//val zoom = ObservableProperty(2.0) { it.clamp(0.25, 20.0) }
	data class PickedSelection(val data: Bitmap32)
	val picked = ObservableProperty(PickedSelection(Bitmap32(1, 1) { _, _ -> RGBA(0) }))
	fun zoomIn() = run { zoomLevel.value++ }
	fun zoomOut() = run { zoomLevel.value-- }
	val selectedLayerIndex = ObservableProperty(0)

	verticalStack {
		//horizontalStack {
		//	height = 32.points
		toolbar {
			iconButton(toolbarIcon("edit.png"))
			button("Dropper")
			button("Eraser")
			button("Fill")
			button("Rect")
			button("Poly")
			button("Point")
			button("Oval")
			//}
			//toolbar {
			iconButton(toolbarIcon("settings.png"))
			iconButton(toolbarIcon("zoomIn.png")) {
				click { zoomIn() }
			}
			iconButton(toolbarIcon("zoomOut.png")) {
				click { zoomOut() }
			}
			//}
			//toolbar {
			button("FlipX")
			button("FlipY")
			button("RotateL")
			button("RotateR")
		}
		//}

		horizontalStack {
			fill()
			verticalStack {
				//minWidth = 132.pt
				minWidth = 228.pt
				width = minWidth
				//width = 20.percentage
				tabs {
					height = 50.percentage
					tab("Properties") {
						verticalStack {
							list(listOf("prop1", "prop2", "prop3")) {
								height = MUnit.Fill
							}
							toolbar {
								iconButton(toolbarIcon("add.png"))
								iconButton(toolbarIcon("edit.png"))
								iconButton(toolbarIcon("delete.png"))
							}
						}
					}
				}
				tabs {
					height = 50.percentage
					tab("Tileset") {
						verticalStack {
							tabs {
								fill()
								for (tileset in tilemap.tilesets) {
									tab("Untitled") {
										val tilemap = tileset.pickerTilemap()
										val mapComponent = MapComponent(tilemap)
										val patternLayer = tilemap.patternLayers.first()
										mapComponent.selectedRange(0, 0)
										var downStart: PointInt? = null
										val zoomLevel = ObservableProperty(zoomLevels.indexOf(100)) { it.clamp(0, zoomLevels.size - 1) }
										fun zoomRatio(): Double = zoomLevels[zoomLevel.value].toDouble() / 100.0
										zoomLevel {
											mapComponent.scale = zoomRatio()
										}
										zoomLevel.trigger()
										mapComponent.onZoom {
											zoomLevel.value += it
										}
										mapComponent.upTileSignal {
											downStart = null
										}
										mapComponent.outTileSignal {
											//downStart = null
										}
										mapComponent.downTileSignal {
											if (downStart == null) {
												downStart = it
											}
											val start = downStart!!
											val xmin = min(start.x, it.x)
											val xmax = max(start.x, it.x)
											val ymin = min(start.y, it.y)
											val ymax = max(start.y, it.y)
											val width = xmax - xmin + 1
											val height = ymax - ymin + 1
											val bmp = Bitmap32(width, height) { x, y -> RGBA(patternLayer.map[xmin + x, ymin + y].value) }
											picked.value = PickedSelection(bmp)
											mapComponent.selectedRange(xmin, ymin, bmp.width, bmp.height)
										}
										this.component.add(JBScrollPane(mapComponent))
									}
								}
							}
							toolbar {
								iconButton(toolbarIcon("add.png"))
								iconButton(toolbarIcon("openDisk.png"))
								iconButton(toolbarIcon("edit.png"))
								iconButton(toolbarIcon("delete.png"))
							}
						}
					}
				}
			}
			verticalStack {
				minWidth = 32.pt
				fill()
				tabs {
					val tabs = component
					fill()
					tab("Map") {
						unsavedChanges {
							println("unsavedChanges: $it")
							tabs.setTitleAt(0, if (it) "Map (*)" else "Map")
							tabs.repaint()
							tabs.invalidate()
							tabs.parent.repaint()
							tabs.parent.invalidate()
						}
						val mapComponent = MapComponent(tilemap)
						mapComponent.onZoom {
							zoomLevel.value += it
						}
						mapComponent.overTileSignal {
							//println("OVER: $it")
							mapComponent.selectedRange(it.x, it.y, picked.value.data.width, picked.value.data.height)
						}
						mapComponent.outTileSignal {
							//println("MOUSE EXIT")
							mapComponent.unselect()
						}

						data class DrawEntry(val x: Int, val y: Int, val layer: Int, val oldColor: RGBA, val newColor: RGBA) {
							fun apply(tmx: TiledMap, redo: Boolean) {
								val layer = tmx.allLayers[layer]
								if (layer is TiledMap.Layer.Patterns) {
									layer.map[x, y] = if (redo) newColor else oldColor
								}
							}
						}
						val bufferDraw = arrayListOf<DrawEntry>()

						mapComponent.upTileSignal {
							if (bufferDraw.isNotEmpty()) {
								val items = bufferDraw.toList()
								history.add("DRAW") { redo ->
									for (item in if (redo) items else items.reversed()) item.apply(mapComponent.tmx, redo = redo)
									mapComponent.mapRepaint(true)
								}
								bufferDraw.clear()
							}
						}
						mapComponent.downTileSignal {
							val pickedData = picked.value.data
							mapComponent.selectedRange(it.x, it.y, pickedData.width, pickedData.height)
							val layerIndex = selectedLayerIndex.value
							val layer = mapComponent.tmx.allLayers.getOrNull(layerIndex)
							if (layer != null && !layer.locked) {
								when (layer) {
									is TiledMap.Layer.Patterns -> {
										//println("DOWN: $it")
										for (y in 0 until pickedData.height) {
											for (x in 0 until pickedData.width) {
												val tx = it.x + x
												val ty = it.y + y
												if (tx !in 0 until layer.map.width) continue
												if (ty !in 0 until layer.map.height) continue
												val drawEntry = DrawEntry(tx, ty, layerIndex, layer.map[tx, ty], pickedData[x, y])
												drawEntry.apply(mapComponent.tmx, redo = true)
												bufferDraw.add(drawEntry)
												//layer.map[it.x + x, it.y + y] = pickedData[x, y]
											}
										}
										mapComponent.mapRepaint(true)
										unsavedChanges.value = true
									}
								}
							}
						}
						mapComponent.downRightTileSignal {
							//println("DOWN_RIGHT: $it")
						}
						updateTilemap {
							mapComponent.mapRepaint(true)
						}
						this.component.add(JBScrollPane(mapComponent))
						zoomLevel { mapComponent.scale = zoomRatio() }
					}
				}
			}
			verticalStack {
				minWidth = 228.pt
				width = minWidth
				//width = 20.percentage
				tabs {
					height = 50.percentage
					tab("Layers") {
						verticalStack {
							fill()
							list(listOf<String>()) {
								val list = this
								height = MUnit.Fill
								selectedLayerIndex { list.component.selectedIndex = it }
								selectedLayerIndex.addAdjuster { if (list.component.model.size == 0) 0 else it.coerceIn(0, list.component.model.size - 1) }
								list.component.addListSelectionListener {
									selectedLayerIndex.value = list.component.selectedIndex
								}
								updateLayersSignal {
									val preservedLayerIndex = selectedLayerIndex.value
									list.component.setListData(tilemap.allLayers.map { "${it.name} [${if (it.visible) "\u2600" else "\u223C" }] [${if (it.locked) "\u274C" else "\u2714" }]" }.toTypedArray())
									list.component.selectedIndex = preservedLayerIndex
									list.component.repaint()
									list.component.parent?.repaint()
								}
								updateLayersSignal(Unit)
							}
							toolbar {
								iconButton(toolbarIcon("add.png")) {
									click {
										showPopupMenu(listOf("Tile Layer", "Object Layer", "Image Layer")) {
											val layerIndex = selectedLayerIndex.value
											val newLayer = TiledMap.Layer.Patterns(Bitmap32(tilemap.width, tilemap.height)).also {
												it.name = "Tile Layer ${tilemap.allLayers.size + 1}"
											}
											history.addAndDo("ADD LAYER") { redo ->
												if (redo) {
													tilemap.allLayers.add(layerIndex, newLayer.clone())
												} else {
													tilemap.allLayers.removeAt(layerIndex)
												}
												updateLayersSignal(Unit)
												selectedLayerIndex.value = if (redo) layerIndex else layerIndex
											}
											println("CLICKED ON: $it")
										}
									}
								}

								fun moveLayer(direction: Int) {
									val name = if (direction < 0) "LAYER UP" else "LAYER DOWN"
									val layerIndex = selectedLayerIndex.value
									history.addAndDo(name) { redo ->
										tilemap.allLayers.swapIndices(layerIndex, layerIndex + direction)
										if (redo) {
											selectedLayerIndex.value = layerIndex + direction
										} else {
											selectedLayerIndex.value = layerIndex
										}
										updateLayersSignal(Unit)
										updateTilemap(Unit)
									}
								}

								iconButton(toolbarIcon("up.png")) {
									updateLayersSignal {
										component.isEnabled = tilemap.allLayers.isNotEmpty()
									}
									click {
										if (selectedLayerIndex.value > 0) {
											moveLayer(-1)
										}
									}
								}
								iconButton(toolbarIcon("down.png")) {
									updateLayersSignal {
										component.isEnabled = tilemap.allLayers.isNotEmpty()
									}
									click {
										if (selectedLayerIndex.value < tilemap.allLayers.size - 1) {
											moveLayer(+1)
										}
									}
								}
								iconButton(toolbarIcon("delete.png")) {
									updateLayersSignal {
										component.isEnabled = tilemap.allLayers.isNotEmpty()
									}
									click {
										val layerIndex = selectedLayerIndex.value
										val layer = tilemap.allLayers.getOrNull(layerIndex)?.clone()
										if (layer != null) {
											history.addAndDo("REMOVE LAYER") { redo ->
												if (redo) {
													tilemap.allLayers.removeAt(layerIndex)
												} else {
													tilemap.allLayers.add(layerIndex, layer.clone())
												}
												updateLayersSignal(Unit)
												selectedLayerIndex.value = (if (redo) layerIndex - 1 else layerIndex)
												updateTilemap(Unit)
											}
										}
									}
								}
								iconButton(toolbarIcon("copy.png")) {
									updateLayersSignal {
										component.isEnabled = tilemap.allLayers.isNotEmpty()
									}
									click {
										val layerIndex = selectedLayerIndex.value
										val layer = tilemap.allLayers.getOrNull(layerIndex)?.clone()
										if (layer != null) {
											history.addAndDo("REMOVE LAYER") { redo ->
												if (redo) {
													tilemap.allLayers.add(layerIndex, layer.clone())
												} else {
													tilemap.allLayers.removeAt(layerIndex)
												}
												updateLayersSignal(Unit)
												selectedLayerIndex.value = (if (redo) layerIndex + 1 else layerIndex)
												updateTilemap(Unit)
											}
										}
									}
								}
								iconButton(toolbarIcon("show.png")) {
									updateLayersSignal {
										component.isEnabled = tilemap.allLayers.isNotEmpty()
									}
									click {
										val layer = tilemap.allLayers.getOrNull(selectedLayerIndex.value)
										if (layer != null) {
											history.addAndDo("TOGGLE VISIBLE") {
												layer.visible = !layer.visible
												updateLayersSignal(Unit)
												updateTilemap(Unit)
											}
										}
									}
								}
								iconButton(toolbarIcon("locked_dark.png")) {
									updateLayersSignal {
										component.isEnabled = tilemap.allLayers.isNotEmpty()
									}
									click {
										val layer = tilemap.allLayers.getOrNull(selectedLayerIndex.value)
										if (layer != null) {
											history.addAndDo("TOGGLE LOCKED") {
												layer.locked = !layer.locked
												updateLayersSignal(Unit)
												updateTilemap(Unit)
											}
										}
									}
								}
							}
						}
					}
				}
				tabs {
					height = 50.percentage
					tab("History") {
						list(listOf<String>()) {
							var preventUpdating = false
							component.addListSelectionListener {
								if (!preventUpdating) {
									if (component.selectedIndex != history.cursor) {
										history.moveTo(component.selectedIndex)
									}
								}
							}
							history.onChange.addCallInit {
								preventUpdating = true
								try {
									component.setListData((listOf("Start") + history.entries.map { it.name }).toTypedArray())
									component.selectedIndex = history.cursor
								} finally {
									preventUpdating = false
								}
								//component.repaintAndInvalidate()
							}
						}
					}
				}
			}
		}
		horizontalStack {
			height = 32.pt
			label("Status Status 2") {
				width = 150.pt
				zoomLevel { component.text = "Zoom: ${"%.0f".format(zoomRatio() * 100)}%" }
			}
			slider(min = 0, max = zoomLevels.size - 1) {
				component.addChangeListener {
					//println("CHANGED")
					zoomLevel.value = component.value
				}
				zoomLevel { component.value = zoomLevel.value }
			}
		}
	}

	zoomLevel.trigger()

	if (registerHistoryShortcuts) {
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { ke ->
			//println("KeyEventDispatcher.ke: $ke")
			when {
				(ke.isControlDown && ke.id == KeyEvent.KEY_PRESSED) && ((ke.keyCode == KeyEvent.VK_Z && ke.isShiftDown) || (ke.keyCode == KeyEvent.VK_Y)) -> {
					history.redo()
					true
				}
				(ke.isControlDown && ke.id == KeyEvent.KEY_PRESSED) && (ke.keyCode == KeyEvent.VK_Z) -> {
					history.undo()
					true
				}
				else -> {
					false
				}
			}
		}
	}
}

private fun TiledMap.TiledTileset.pickerTilemap(): TiledMap {
	val tileset = this.tileset
	val mapWidth = this.data.columns.takeIf { it >= 0 } ?: (this.tileset.width / this.data.tilewidth)
	val mapHeight = Math.ceil(this.data.tilecount.toDouble() / this.data.columns.toDouble()).toInt()

	return TiledMap(TiledMapData(
		width = mapWidth, height = mapHeight,
		tilewidth = tileset.width, tileheight = tileset.height,
		allLayers = arrayListOf(TiledMap.Layer.Patterns(Bitmap32(mapWidth.coerceAtLeast(1), mapHeight.coerceAtLeast(1)) { x, y -> RGBA(y * mapWidth + x) }))
	), listOf(this), tileset)
}


class MyTileMapEditorPanel(
		val tmxFile: VfsFile,
		val history: HistoryManager = HistoryManager(),
		val registerHistoryShortcuts: Boolean = true,
		val onSaveXml: (String) -> Unit = {}
) : JPanel(BorderLayout()) {
	val tmx = runBlocking { tmxFile.readTiledMap() }
	init {
		styled.createTileMapEditor(tmx, history, registerHistoryShortcuts)
		history.onSave {
			runBlocking {
				val xmlString = tmx.toXML().toString()
				onSaveXml(xmlString)
				//tmxFile.writeString(xmlString)
			}
		}
		//history.onChange {
		history.onAdd {
			history.save()
		}
	}
/*
	val realPanel = tileMapEditor.contentPanel

	val mapComponent = MapComponent(tmx)

	var scale: Double
		get() = mapComponent.scale
		set(value) = run { mapComponent.scale = value }

	val mapComponentScroll = JBScrollPane(mapComponent).also { scroll ->
		//scroll.verticalScrollBar.unitIncrement = 16
	}

	fun updatedSize() {
		tileMapEditor.leftSplitPane.dividerLocation = 200
		tileMapEditor.rightSplitPane.dividerLocation = tileMapEditor.rightSplitPane.width - 200
	}

	val layersController = LayersController(tileMapEditor.layersPane)
	val propertiesController = PropertiesController(tileMapEditor.propertiesPane)

	init {

		add(realPanel, BorderLayout.CENTER)

		tileMapEditor.mapPanel.add(mapComponentScroll, GridConstraints().also { it.fill = GridConstraints.FILL_BOTH })

		tileMapEditor.zoomInButton.addActionListener { scale *= 1.5 }
		tileMapEditor.zoomOutButton.addActionListener { scale /= 1.5 }


		updatedSize()
		addComponentListener(object : ComponentAdapter() {
			override fun componentResized(e: ComponentEvent) {
				updatedSize()
			}
		})
	}
 */
}

/*
class PropertiesController(val panel: PropertiesPane) {
	var width = 100
	var height = 100
	val propertyTable = KorgePropertyTable(KorgePropertyTable.Properties().register(::width,::height)).also {
		panel.tablePane.add(JScrollPane(it), BorderLayout.CENTER)
	}
}

class LayersController(val panel: LayersPane) {
	init {
		val menu = JPopupMenu("Menu").apply {
			add("Tile Layer")
			add("Object Layer")
			add("Image Layer")
		}

		panel.newButton.addActionListener {
			menu.show(panel.newButton, 0, panel.newButton.height)
		}
	}
}
 */

class MyTileMapEditorFrame(val tmxFile: VfsFile) : JFrame() {
	init {
		contentPane = MyTileMapEditorPanel(tmxFile)
		pack()
	}

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			val frame = MyTileMapEditorFrame(localCurrentDirVfs["samples/gfx/sample.tmx"])
			frame.defaultCloseOperation = EXIT_ON_CLOSE
			frame.setLocationRelativeTo(null)
			frame.isVisible = true
		}
	}
}

inline fun Bitmap32.anyFixed(callback: (RGBA) -> Boolean): Boolean = (0 until area).any { callback(data[it]) }
inline fun Bitmap32.allFixed(callback: (RGBA) -> Boolean): Boolean = (0 until area).all { callback(data[it]) }