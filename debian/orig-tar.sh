#!/bin/sh -e

TAR=../jetty_$2.orig.tar.gz
DIR=jetty-$2
TAG=$(echo jetty-$2 | sed 's,~alpha,-alpha-,')

svn export http://svn.codehaus.org/jetty/jetty/tags/$TAG/ $DIR
rm -r $DIR/modules/jsp-api-2.0
rm -r $DIR/extras/win32service
rm -r $DIR/contrib/cometd
rm -r $DIR/contrib/rpms
rm -r $DIR/contrib/debian
rm -r $DIR/contrib/grizzly
rm -r $DIR/contrib/maven-beanshell-plugin

tar -c -z -f $TAR $DIR
# drop ../$TAG to avoid "self-destruct" behavior
#rm -rf $DIR ../$TAG
rm -rf $DIR

# move to directory 'tarballs'
if [ -r .svn/deb-layout ]; then
  . .svn/deb-layout
  mv $TAR $origDir
  echo "moved $TAR to $origDir"
fi

