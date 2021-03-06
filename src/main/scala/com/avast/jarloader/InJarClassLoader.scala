package com.avast.jarloader

import java.lang.reflect.Field
import java.net.{JarURLConnection, URL}
import java.util
import java.util.jar.{Attributes, Manifest}

import org.slf4j.LoggerFactory
import org.xeustechnologies.jcl.{ClasspathResources, JarClassLoader}

import scala.collection.JavaConversions._

/**
 * Created <b>19.8.2014</b><br>
 *
 * @author Jenda Kolena, kolena@avast.com
 */
class InJarClassLoader(jar: JarURLConnection) {
  val LOG = LoggerFactory.getLogger(getClass)

  val loader = new InternalLoader
  loader.add(jar.getJarFileURL)

  val version = jar.getMainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION)

  val packages = new util.HashSet[String]()

  LOG.debug("Loading package list")

  loader.getLoadedResources.keySet().foreach(rs => {
    val i = rs.lastIndexOf('/')
    if (i >= 0) {
      val pkg = rs.substring(0, i)
      if (!"META-INF".equals(pkg))
        packages.add(pkg.replaceAll("/", "."))
    } //else default package
  })

  LOG.debug("Loaded " + packages.size() + " packages")

  def release() = {
    val f = loader.getClass.getSuperclass.getDeclaredField("classes")
    f.setAccessible(true)
    var map = f.get(loader).asInstanceOf[util.Map[_, _]]
    map.clear()

    val f2 = loader.getClass.getSuperclass.getDeclaredField("classpathResources")
    f2.setAccessible(true)
    val res = f2.get(loader).asInstanceOf[ClasspathResources]
    val f3: Field = res.getClass.getSuperclass.getDeclaredField("jarEntryContents")
    f3.setAccessible(true)
    map = f3.get(res).asInstanceOf[util.Map[String, Array[Byte]]]
    map.clear()

    System.gc()
  }

  def getLoader: JarClassLoader = loader

  def getManifest: Manifest = jar.getManifest

  class InternalLoader extends JarClassLoader {
    override def getPackage(name: String): Package = {
      if (!packages.contains(name)) return super.getPackage(name)

      val parPkg = super.getPackage(name)

      if (parPkg != null)
        decoratePackage(parPkg)

      parPkg
    }

    protected def decoratePackage(pkg: Package) {
      if (pkg == null) return

      try {
        var f = pkg.getClass.getDeclaredField("implVersion")
        f.setAccessible(true)
        f.set(pkg, version)
        f.setAccessible(false)

        f = pkg.getClass.getDeclaredField("implVendor")
        f.setAccessible(true)
        f.set(pkg, getManifest.getMainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VENDOR))
        f.setAccessible(false)

        f = pkg.getClass.getDeclaredField("implTitle")
        f.setAccessible(true)
        f.set(pkg, getManifest.getMainAttributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE))
        f.setAccessible(false)

        f = pkg.getClass.getDeclaredField("loader")
        f.setAccessible(true)
        f.set(pkg, this)
        f.setAccessible(false)
      }
      catch {
        case e: Exception => LOG.warn("Cannot decorate package info for " + pkg.getName, e)
      }
    }

    override def getPackages: Array[Package] = {
      val pkgs = super.getPackages

      pkgs.foreach(p => {
        if (packages.contains(p.getName)) decoratePackage(p)
      })

      pkgs
    }

    override def definePackage(name: String, specTitle: String, specVersion: String, specVendor: String, implTitle: String, implVersion: String, implVendor: String, sealBase: URL): Package = {
      super.definePackage(name, specTitle, specVersion, specVendor, getManifest.getMainAttributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE), version, getManifest.getMainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VENDOR), sealBase)
    }
  }

}
