tell application "Safari" to activate

tell application "System Events"
  tell process "Safari"
    tell menu bar 1
      tell menu bar item "Develop"
	tell menu 1
	  click menu item "Allow Remote Automation"
	end tell
      end tell
    end tell
  end tell
end tell
