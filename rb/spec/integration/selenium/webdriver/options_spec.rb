# encoding: utf-8
#
# Licensed to the Software Freedom Conservancy (SFC) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The SFC licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

require_relative 'spec_helper'

module Selenium
  module WebDriver
    describe Options, except: {browser: :safari} do
      describe 'logs', except: {browser: %i[firefox ie edge ff_nightly]} do
        it 'can fetch remote log types', only: {driver: :remote} do
          expect(driver.manage.logs.available_types).to include(:server, :client)
        end

        # Phantomjs Returns har instead of driver
        it 'can fetch available log types', except: {browser: :phantomjs} do
          expect(driver.manage.logs.available_types).to include(:browser, :driver)
        end

        # All other browsers show empty
        it 'can get the browser log', only: {browser: %i[firefox ff_esr ff_nightly]} do
          driver.navigate.to url_for('simpleTest.html')

          entries = driver.manage.logs.get(:browser)
          expect(entries).not_to be_empty
          expect(entries.first).to be_kind_of(LogEntry)
        end

        # Phantomjs Returns har instead of driver
        # Chrome - turned off by default
        it 'can get the driver log', except: {browser: %i[phantomjs chrome]} do
          driver.navigate.to url_for('simpleTest.html')

          entries = driver.manage.logs.get(:driver)
          expect(entries).not_to be_empty
          expect(entries.first).to be_kind_of(LogEntry)
        end
      end

      describe 'cookie management', except: {browser: :phantomjs} do
        it 'should get all' do
          driver.navigate.to url_for('xhtmlTest.html')
          driver.manage.add_cookie name: 'foo', value: 'bar'

          cookies = driver.manage.all_cookies

          expect(cookies.size).to eq(1)
          expect(cookies.first[:name]).to eq('foo')
          expect(cookies.first[:value]).to eq('bar')
        end

        # Firefox - https://bugzilla.mozilla.org/show_bug.cgi?id=1282970
        # IE - Command not implemented
        it 'should get named cookie', except: {driver: [:firefox, :ff_nightly, :ie]} do
          driver.navigate.to url_for('xhtmlTest.html')
          driver.manage.add_cookie name: 'foo', value: 'bar'

          expect(driver.manage.cookie_named('foo')[:value]).to eq('bar')
        end

        # Edge BUG - https://developer.microsoft.com/en-us/microsoft-edge/platform/issues/5751773/
        it 'should delete one', except: {browser: :edge} do
          driver.navigate.to url_for('xhtmlTest.html')
          driver.manage.add_cookie name: 'foo', value: 'bar'

          driver.manage.delete_cookie('foo')
          expect(driver.manage.all_cookies.find { |c| c[:name] == 'foo' }).to be_nil
        end

        it 'should delete all', except: {browser: :edge} do
          driver.navigate.to url_for('xhtmlTest.html')

          driver.manage.add_cookie name: 'foo', value: 'bar'
          driver.manage.add_cookie name: 'bar', value: 'foo'
          driver.manage.delete_all_cookies
          expect(driver.manage.all_cookies).to be_empty
        end

        # Firefox - https://bugzilla.mozilla.org/show_bug.cgi?id=1256007
        # IE - Command not implemented
        it 'should use DateTime for expires', except: {browser: %i[firefox ff_nightly ie]} do
          driver.navigate.to url_for('xhtmlTest.html')

          expected = DateTime.new(2039)
          driver.manage.add_cookie name: 'foo',
                                   value: 'bar',
                                   expires: expected

          actual = driver.manage.cookie_named('foo')[:expires]
          expect(actual).to be_kind_of(DateTime)
          expect(actual).to eq(expected)
        end
      end
    end # Options
  end # WebDriver
end # Selenium
